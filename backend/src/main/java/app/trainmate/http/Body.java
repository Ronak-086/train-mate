package app.trainmate.http;

import app.trainmate.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.Collection;

/** A parsed JSON request body with the validators the API uses. Every failure is a 400 with a sentence a person can read. */
public final class Body {
    private final JsonNode root;

    private Body(JsonNode root) { this.root = root; }

    public static Body parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return new Body(null);
        try {
            JsonNode n = Json.tree(bytes);
            if (n == null || n.isMissingNode() || n.isNull()) return new Body(null);
            if (!n.isObject()) throw ApiException.bad("Request body must be a JSON object.");
            return new Body(n);
        } catch (java.io.IOException e) {
            throw ApiException.bad("INVALID_JSON", "Request body is not valid JSON.");
        }
    }

    private JsonNode node(String key) {
        if (root == null) return null;
        JsonNode n = root.get(key);
        return n == null || n.isNull() ? null : n;
    }

    /** Optional trimmed text within length bounds; null when absent or blank. */
    public String str(String key, int min, int max, boolean required) {
        JsonNode n = node(key);
        if (n == null || (n.isTextual() && n.asText().trim().isEmpty())) {
            if (required) throw ApiException.bad(key + " is required.");
            return null;
        }
        if (!n.isTextual()) throw ApiException.bad(key + " must be text.");
        String s = n.asText().trim();
        if (s.length() < min) throw ApiException.bad(key + " is too short.");
        if (s.length() > max) throw ApiException.bad(key + " must be at most " + max + " characters.");
        return s;
    }

    public Integer integer(String key, int min, int max, boolean required) {
        JsonNode n = node(key);
        if (n == null) {
            if (required) throw ApiException.bad(key + " is required.");
            return null;
        }
        if (!n.isIntegralNumber() || n.asLong() < min || n.asLong() > max) {
            throw ApiException.bad(key + " must be a whole number from " + min + " to " + max + ".");
        }
        return n.asInt();
    }

    public String oneOf(String key, Collection<String> allowed, boolean required) {
        JsonNode n = node(key);
        if (n == null) {
            if (required) throw ApiException.bad(key + " is required.");
            return null;
        }
        if (!n.isTextual() || !allowed.contains(n.asText())) throw ApiException.bad(key + " must be one of: " + String.join(", ", allowed) + ".");
        return n.asText();
    }

    /** A YYYY-MM-DD date. */
    public static LocalDate date(String value, String name) {
        try {
            if (value == null) throw new IllegalArgumentException();
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            throw ApiException.bad(name + " must be YYYY-MM-DD.");
        }
    }

    public String raw(String key) {
        JsonNode n = node(key);
        return n != null && n.isTextual() ? n.asText() : null;
    }

    public Boolean bool(String key, boolean defaultValue) {
        JsonNode n = node(key);
        if (n == null) return defaultValue;
        if (!n.isBoolean()) throw ApiException.bad(key + " must be true or false.");
        return n.asBoolean();
    }

    public java.util.UUID uuid(String key, boolean required) {
        String s = str(key, 1, 64, required);
        if (s == null) return null;
        try {
            return java.util.UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.bad(key + " must be a valid UUID.");
        }
    }
}
