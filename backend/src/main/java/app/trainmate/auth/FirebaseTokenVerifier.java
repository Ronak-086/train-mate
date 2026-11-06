package app.trainmate.auth;

import app.trainmate.http.ApiException;
import app.trainmate.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Base64;

/**
 * Verifies a Firebase Auth ID token (an RS256 JWT) per Firebase's documented rules, using only the JDK's crypto.
 * Order matters: the algorithm and key id are read first, the signature is checked against Google's key,
 * and only then are any claims believed.
 *
 * Accepted only if: alg is RS256 (never "none" or HS256), the signature verifies, iss is
 * https://securetoken.google.com/{projectId}, aud is {projectId}, exp is in the future, iat and auth_time are not
 * in the future (60s leeway), and sub is a non-empty string of at most 128 characters.
 */
public final class FirebaseTokenVerifier implements TokenVerifier {
    private static final long LEEWAY_SECONDS = 60;
    private static final String INVALID = "Your sign-in isn't valid. Please sign in again.";

    private final String projectId;
    private final JwksSource keys;
    private final Clock clock;

    public FirebaseTokenVerifier(String projectId, JwksSource keys, Clock clock) {
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("Firebase project id is required");
        this.projectId = projectId;
        this.keys = keys;
        this.clock = clock;
    }

    @Override
    public AuthUser verify(String token) {
        try {
            if (token == null) throw reject();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) throw reject();

            JsonNode header = Json.tree(b64(parts[0]));
            if (!"RS256".equals(header.path("alg").asText())) throw reject();
            String kid = header.path("kid").asText("");
            if (kid.isEmpty()) throw reject();
            PublicKey key = keys.key(kid).orElseThrow(FirebaseTokenVerifier::reject);

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(b64(parts[2]))) throw reject();

            JsonNode c = Json.tree(b64(parts[1]));
            long now = clock.millis() / 1000;
            if (!("https://securetoken.google.com/" + projectId).equals(c.path("iss").asText())) throw reject();
            if (!projectId.equals(c.path("aud").asText())) throw reject();
            if (!c.path("exp").isIntegralNumber() || c.path("exp").asLong() <= now) throw reject();
            if (!c.path("iat").isIntegralNumber() || c.path("iat").asLong() > now + LEEWAY_SECONDS) throw reject();
            if (c.hasNonNull("auth_time") && c.path("auth_time").asLong() > now + LEEWAY_SECONDS) throw reject();
            String sub = c.path("sub").asText("");
            if (sub.isEmpty() || sub.length() > 128) throw reject();

            return new AuthUser(sub, c.hasNonNull("email") ? c.get("email").asText() : null, c.hasNonNull("name") ? c.get("name").asText() : null);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw reject(); // anything malformed is simply "not valid"
        }
    }

    private static byte[] b64(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    private static ApiException reject() {
        return ApiException.unauthenticated(INVALID);
    }
}
