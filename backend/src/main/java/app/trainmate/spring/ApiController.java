package app.trainmate.spring;

import app.trainmate.Wiring;
import app.trainmate.http.ApiException;
import app.trainmate.http.Req;
import app.trainmate.http.Res;
import app.trainmate.util.Ist;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The one controller: every /api/** request goes to the tested, framework-free handler. */
@RestController
public class ApiController {
    private static final int MAX_BODY_BYTES = 100_000;
    private final Wiring.App app;
    private final Wiring.Settings settings;

    ApiController(Wiring.App app, Wiring.Settings settings) {
        this.app = app;
        this.settings = settings;
    }

    @RequestMapping("/api/**")
    public ResponseEntity<byte[]> handle(HttpServletRequest request) throws IOException {
        Res res;
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            res = Res.problem(new ApiException(413, "PAYLOAD_TOO_LARGE", "That request is too large."));
        } else {
            Map<String, String> headers = new LinkedHashMap<>();
            for (String name : Collections.list(request.getHeaderNames())) headers.putIfAbsent(name.toLowerCase(Locale.ROOT), request.getHeader(name));
            res = app.handler().handle(new Req(request.getMethod(), request.getRequestURI(), Req.parseQuery(request.getQueryString()), headers, body));
        }
        ResponseEntity.BodyBuilder out = ResponseEntity.status(res.status());
        res.headers().forEach(out::header);
        if (res.contentType() != null) out.contentType(MediaType.parseMediaType(res.contentType()));
        // A movable development clock reaches the browser here; real servers just send Date.
        if (settings.devTools()) out.header("X-Server-Time", Ist.iso(app.clock().instant()));
        return out.body(res.body());
    }
}
