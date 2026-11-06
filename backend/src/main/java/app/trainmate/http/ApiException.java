package app.trainmate.http;

import java.util.LinkedHashMap;
import java.util.Map;

/** A failure with a status, a stable machine-readable code, and a sentence safe to show a person. Rendered as RFC 9457 problem+json. */
@SuppressWarnings("serial")
public class ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, Object> extra;

    public ApiException(int status, String code, String detail, Map<String, Object> extra) {
        super(detail);
        this.status = status;
        this.code = code;
        this.extra = extra == null ? Map.of() : extra;
    }

    public ApiException(int status, String code, String detail) {
        this(status, code, detail, null);
    }

    public int status() { return status; }
    public String code() { return code; }
    public Map<String, Object> extra() { return extra; }

    public static ApiException bad(String detail) { return new ApiException(400, "VALIDATION_ERROR", detail); }
    public static ApiException bad(String code, String detail) { return new ApiException(400, code, detail); }
    public static ApiException unauthenticated(String detail) { return new ApiException(401, "UNAUTHENTICATED", detail); }
    public static ApiException forbidden(String code, String detail) { return new ApiException(403, code, detail); }
    public static ApiException notFound() { return new ApiException(404, "NOT_FOUND", "Not found."); }
    public static ApiException notFound(String detail) { return new ApiException(404, "NOT_FOUND", detail); }
    public static ApiException notFound(String code, String detail) { return new ApiException(404, code, detail); }
    public static ApiException conflict(String code, String detail) { return new ApiException(409, code, detail); }
    public static ApiException gone(String code, String detail) { return new ApiException(410, code, detail); }

    public static ApiException tooMany(String code, String detail, long retryAfterSeconds) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("retryAfterSeconds", Math.max(1, retryAfterSeconds));
        return new ApiException(429, code, detail, extra);
    }
}
