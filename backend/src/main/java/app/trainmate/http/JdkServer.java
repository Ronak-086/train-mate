package app.trainmate.http;

import app.trainmate.util.Ist;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Hosts an {@link ApiHandler} on the JDK's built-in HTTP server. Used for local runs and for testing the whole stack
 * over real sockets. Production uses Spring Boot's server instead (see the spring package).
 */
public final class JdkServer implements AutoCloseable {
    static final int MAX_BODY_BYTES = 100_000;
    private final HttpServer server;
    private final ApiHandler handler;
    private final Clock dateClock;

    /** @param dateClock when non-null, responses carry an X-Server-Time header from this clock (so a mock clock reaches the browser) */
    public JdkServer(ApiHandler handler, int port, Clock dateClock) throws IOException {
        this.handler = handler;
        this.dateClock = dateClock;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/", this::serve);
        this.server.setExecutor(Executors.newFixedThreadPool(16));
    }

    public JdkServer start() {
        server.start();
        return this;
    }

    public int port() { return server.getAddress().getPort(); }

    @Override public void close() { server.stop(0); }

    private void serve(HttpExchange ex) throws IOException {
        try (ex) {
            Res res;
            try {
                byte[] body = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                if (body.length > MAX_BODY_BYTES) {
                    res = Res.problem(new ApiException(413, "PAYLOAD_TOO_LARGE", "That request is too large."));
                } else {
                    Map<String, String> headers = new LinkedHashMap<>();
                    ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v.get(0)));
                    res = handler.handle(new Req(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), Req.parseQuery(ex.getRequestURI().getRawQuery()), headers, body));
                }
            } catch (RuntimeException e) {
                res = Res.problem(new ApiException(500, "INTERNAL_ERROR", "Something went wrong on our side."));
            }
            res.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
            if (res.contentType() != null) ex.getResponseHeaders().set("Content-Type", res.contentType());
            // The JDK server always overwrites Date with real time, so a movable (development) clock travels in its own header.
            if (dateClock != null) ex.getResponseHeaders().set("X-Server-Time", Ist.iso(dateClock.instant()));
            byte[] out = res.body();
            if (res.status() == 204 || out.length == 0) {
                ex.sendResponseHeaders(res.status(), -1);
            } else {
                ex.sendResponseHeaders(res.status(), out.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            }
        }
    }
}
