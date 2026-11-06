package app.trainmate.auth;

import app.trainmate.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google's public keys for Firebase ID tokens, fetched over HTTPS and cached for as long as Google's Cache-Control says.
 * An unknown key id triggers at most one refetch a minute (Google rotates keys), so a flood of forged tokens
 * can't make the server hammer Google.
 */
public final class GoogleJwksSource implements JwksSource {
    public static final String FIREBASE_JWKS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
    private static final Pattern MAX_AGE = Pattern.compile("max-age=(\\d+)");
    private static final long MIN_REFETCH_MS = 60_000;

    private final HttpClient http;
    private final URI uri;
    private final Clock clock;
    private Map<String, PublicKey> keys = Map.of();
    private long expiresAtMs = 0;
    private long lastFetchMs = 0;

    public GoogleJwksSource(String url, Clock clock) {
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        this.uri = URI.create(url);
        this.clock = clock;
    }

    @Override
    public synchronized Optional<PublicKey> key(String kid) {
        long now = clock.millis();
        if (now >= expiresAtMs) refresh(now);
        PublicKey k = keys.get(kid);
        if (k == null && now - lastFetchMs >= MIN_REFETCH_MS) {
            refresh(now);
            k = keys.get(kid);
        }
        return Optional.ofNullable(k);
    }

    private void refresh(long now) {
        lastFetchMs = now;
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return; // keep the old keys; try again later
            Map<String, PublicKey> fresh = parse(res.body());
            if (fresh.isEmpty()) return;
            keys = fresh;
            Matcher m = MAX_AGE.matcher(res.headers().firstValue("cache-control").orElse(""));
            long maxAge = m.find() ? Long.parseLong(m.group(1)) : 3600;
            expiresAtMs = now + Math.max(300, maxAge) * 1000;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // Network trouble: keep serving with the keys we have.
        }
    }

    /** Parses a JWKS document into RSA public keys by key id. Non-RSA or malformed entries are skipped. */
    public static Map<String, PublicKey> parse(String jwks) throws java.io.IOException {
        Map<String, PublicKey> out = new HashMap<>();
        JsonNode list = Json.tree(jwks).get("keys");
        if (list == null || !list.isArray()) return out;
        for (JsonNode k : list) {
            try {
                if (!"RSA".equals(k.path("kty").asText()) || !k.hasNonNull("kid")) continue;
                BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("n").asText()));
                BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("e").asText()));
                out.put(k.get("kid").asText(), KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e)));
            } catch (Exception ignored) {
                // skip a bad key rather than failing every sign-in
            }
        }
        return out;
    }
}
