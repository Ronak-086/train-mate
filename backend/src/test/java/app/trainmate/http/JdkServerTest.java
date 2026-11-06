package app.trainmate.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.trainmate.TestDb;
import app.trainmate.Wiring;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The whole stack over real sockets: HTTP in, Postgres underneath. */
class JdkServerTest {
    JdkServer server;
    HttpClient http = HttpClient.newHttpClient();
    String base;

    @BeforeEach void start() throws Exception {
        assumeTrue(TestDb.available(), "No PostgreSQL: skipping");
        TestDb.migratedDb();
        Wiring.App app = Wiring.build(new Wiring.Settings("dev", null, "", null, "mock", 900, true), TestDb.dataSource());
        server = new JdkServer(app.handler(), 0, app.clock()).start();
        base = "http://localhost:" + server.port();
        send("POST", "/api/dev/reset", null, null);
        send("POST", "/api/dev/clock", null, "{\"iso\":\"2026-10-25T09:06:00+05:30\"}");
    }

    @AfterEach void stop() { if (server != null) server.close(); }

    HttpResponse<String> send(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void servesJsonWithTheRightHeaders() throws Exception {
        var r = send("GET", "/api/health", null, null);
        assertEquals(200, r.statusCode());
        assertEquals("application/json", r.headers().firstValue("content-type").orElse(""));
        assertEquals("no-store", r.headers().firstValue("cache-control").orElse(""));
        assertTrue(r.body().contains("2026-10-25T09:06:00+05:30"));
    }

    @Test void aMovableDevClockTravelsInXServerTimeBecauseTheJdkServerOverwritesDate() throws Exception {
        var r = send("GET", "/api/health", null, null);
        assertEquals("2026-10-25T09:06:00+05:30", r.headers().firstValue("x-server-time").orElseThrow());
        assertTrue(r.headers().firstValue("date").isPresent(), "a normal Date header is still sent");
    }

    @Test void errorsAreProblemJsonWithRealStatusCodes() throws Exception {
        var r = send("GET", "/api/me", null, null);
        assertEquals(401, r.statusCode());
        assertEquals("application/problem+json", r.headers().firstValue("content-type").orElse(""));
        assertTrue(r.body().contains("\"code\":\"UNAUTHENTICATED\""));
        assertEquals(404, send("GET", "/api/nope", null, null).statusCode());
    }

    @Test void noContentResponsesHaveNoBody() throws Exception {
        var made = send("POST", "/api/trips", "dev:u_priya", "{\"trainNo\":\"12346\",\"journeyDate\":\"2026-10-25\",\"boardingStn\":\"GHY\",\"destStn\":\"NDLS\",\"travelClass\":\"SL\",\"coach\":\"S1\"}");
        assertEquals(201, made.statusCode());
        String id = made.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        var del = send("DELETE", "/api/trips/" + id, "dev:u_priya", null);
        assertEquals(204, del.statusCode());
        assertEquals("", del.body());
    }

    @Test void queryStringsAreDecoded() throws Exception {
        assertTrue(send("GET", "/api/trains/search?q=sample%20mail", null, null).body().contains("Sample Mail"));
        assertTrue(send("GET", "/api/trains/12345/status?date=2026-10-25", "dev:u_me", null).body().contains("\"delayMinutes\":12"));
    }

    @Test void oversizedBodiesAreRefused() throws Exception {
        var r = send("POST", "/api/trips", "dev:u_me", "{\"x\":\"" + "a".repeat(150_000) + "\"}");
        assertEquals(413, r.statusCode());
        assertTrue(r.body().contains("PAYLOAD_TOO_LARGE"));
    }

    @Test void multiByteTextSurvivesTheRoundTrip() throws Exception {
        send("PATCH", "/api/me", "dev:u_me", "{\"displayName\":\"अनु शर्मा ✓\"}");
        var r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/me")).header("Authorization", "Bearer dev:u_me").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertTrue(new String(r.body(), StandardCharsets.UTF_8).contains("अनु शर्मा ✓"));
    }

    @Test void handlesManyRequestsAtOnce() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> fs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            fs.add(http.sendAsync(HttpRequest.newBuilder(URI.create(base + "/api/trains/12345/status?date=2026-10-25")).header("Authorization", "Bearer dev:u_me").build(), HttpResponse.BodyHandlers.ofString()));
        }
        for (var f : fs) {
            var r = f.get();
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"delayMinutes\":12"));
        }
    }
}
