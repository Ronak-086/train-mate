package app.trainmate.http;

import app.trainmate.util.Json;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** A framework-neutral HTTP response. */
public record Res(int status, String contentType, byte[] body, Map<String, String> headers) {
    public static Res json(int status, Object body) {
        return new Res(status, "application/json", Json.write(body), Map.of("Cache-Control", "no-store"));
    }

    public static Res ok(Object body) {
        return json(200, body);
    }

    public static Res noContent() {
        return new Res(204, null, new byte[0], Map.of("Cache-Control", "no-store"));
    }

    public static Res problem(ApiException e) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "https://trainmate.example/problems/" + e.code().toLowerCase(Locale.ROOT).replace('_', '-'));
        p.put("title", title(e.status()));
        p.put("status", e.status());
        p.put("detail", e.getMessage());
        p.put("code", e.code());
        p.putAll(e.extra());
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Cache-Control", "no-store");
        if (e.status() == 429 && e.extra().get("retryAfterSeconds") != null) h.put("Retry-After", String.valueOf(e.extra().get("retryAfterSeconds")));
        return new Res(e.status(), "application/problem+json", Json.write(p), h);
    }

    public Res withHeader(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new Res(status, contentType, body, h);
    }

    private static String title(int status) {
        return switch (status) {
            case 400 -> "Bad request";
            case 401 -> "Not signed in";
            case 403 -> "Not allowed";
            case 404 -> "Not found";
            case 405 -> "Method not allowed";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 429 -> "Too many requests";
            default -> status >= 500 ? "Server error" : "Error";
        };
    }
}
