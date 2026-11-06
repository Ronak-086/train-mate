package app.trainmate.auth;

import static org.junit.jupiter.api.Assertions.*;

import app.trainmate.dev.MutableClock;
import app.trainmate.http.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FirebaseTokenVerifierTest {
    static final String PROJECT = "train-mate-test";
    static final Instant NOW = Instant.parse("2026-10-25T03:36:00Z");
    static KeyPair good, other;
    final MutableClock clock = new MutableClock(NOW);
    final JwksSource jwks = kid -> "k1".equals(kid) ? Optional.of(good.getPublic()) : Optional.<PublicKey>empty();
    final FirebaseTokenVerifier verifier = new FirebaseTokenVerifier(PROJECT, jwks, clock);

    @BeforeAll static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        good = g.generateKeyPair();
        other = g.generateKeyPair();
    }

    static String b64(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    String claims(String iss, String aud, long exp, long iat, String sub) {
        return "{\"iss\":\"" + iss + "\",\"aud\":\"" + aud + "\",\"exp\":" + exp + ",\"iat\":" + iat + ",\"auth_time\":" + iat
            + ",\"sub\":\"" + sub + "\",\"email\":\"asha@example.com\",\"name\":\"Asha Kumar\"}";
    }

    String validClaims() {
        long n = NOW.getEpochSecond();
        return claims("https://securetoken.google.com/" + PROJECT, PROJECT, n + 3600, n - 10, "uid-123");
    }

    String sign(String headerJson, String claimsJson, KeyPair kp) throws Exception {
        String signingInput = b64(headerJson) + "." + b64(claimsJson);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
    }

    String token(String claimsJson) throws Exception { return sign("{\"alg\":\"RS256\",\"kid\":\"k1\",\"typ\":\"JWT\"}", claimsJson, good); }

    void assertRejected(String token) {
        ApiException e = assertThrows(ApiException.class, () -> verifier.verify(token));
        assertEquals(401, e.status());
        assertEquals("UNAUTHENTICATED", e.code());
        assertEquals("Your sign-in isn't valid. Please sign in again.", e.getMessage(), "no internal detail leaks to the caller");
    }

    @Test void acceptsAValidToken() throws Exception {
        AuthUser u = verifier.verify(token(validClaims()));
        assertEquals(new AuthUser("uid-123", "asha@example.com", "Asha Kumar"), u);
    }

    @Test void emailAndNameAreOptional() throws Exception {
        long n = NOW.getEpochSecond();
        String c = "{\"iss\":\"https://securetoken.google.com/" + PROJECT + "\",\"aud\":\"" + PROJECT + "\",\"exp\":" + (n + 60) + ",\"iat\":" + n + ",\"sub\":\"phone-user\"}";
        assertEquals(new AuthUser("phone-user", null, null), verifier.verify(token(c)));
    }

    @Test void rejectsAnUnsignedToken() throws Exception {
        String h = b64("{\"alg\":\"none\",\"kid\":\"k1\"}");
        assertRejected(h + "." + b64(validClaims()) + ".");
        assertRejected(h + "." + b64(validClaims()) + ".AAAA");
    }

    @Test void rejectsAlgorithmConfusion() throws Exception {
        // The classic attack: sign with HS256 using the public key as the HMAC secret.
        String h = b64("{\"alg\":\"HS256\",\"kid\":\"k1\"}");
        String signingInput = h + "." + b64(validClaims());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(good.getPublic().getEncoded(), "HmacSHA256"));
        String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
        assertRejected(signingInput + "." + sig);
        assertRejected(sign("{\"alg\":\"RS512\",\"kid\":\"k1\"}", validClaims(), good));
        assertRejected(sign("{\"kid\":\"k1\"}", validClaims(), good));
    }

    @Test void rejectsASignatureFromAnotherKey() throws Exception {
        assertRejected(sign("{\"alg\":\"RS256\",\"kid\":\"k1\"}", validClaims(), other));
    }

    @Test void rejectsATamperedPayload() throws Exception {
        String[] p = token(validClaims()).split("\\.");
        String forged = validClaims().replace("uid-123", "uid-admin");
        assertRejected(p[0] + "." + b64(forged) + "." + p[2]);
    }

    @Test void rejectsUnknownOrMissingKeyIds() throws Exception {
        assertRejected(sign("{\"alg\":\"RS256\",\"kid\":\"nope\"}", validClaims(), good));
        assertRejected(sign("{\"alg\":\"RS256\"}", validClaims(), good));
        assertRejected(sign("{\"alg\":\"RS256\",\"kid\":\"\"}", validClaims(), good));
    }

    @Test void rejectsTheWrongIssuerOrAudience() throws Exception {
        long n = NOW.getEpochSecond();
        assertRejected(token(claims("https://securetoken.google.com/someone-else", PROJECT, n + 60, n, "u")));
        assertRejected(token(claims("https://accounts.google.com", PROJECT, n + 60, n, "u")));
        assertRejected(token(claims("https://securetoken.google.com/" + PROJECT, "someone-else", n + 60, n, "u")));
    }

    @Test void rejectsExpiredTokens() throws Exception {
        long n = NOW.getEpochSecond();
        String iss = "https://securetoken.google.com/" + PROJECT;
        assertRejected(token(claims(iss, PROJECT, n - 1, n - 3600, "u")));
        assertRejected(token(claims(iss, PROJECT, n, n - 3600, "u"))); // exp == now is already expired
    }

    @Test void aTokenExpiresAsTheClockMovesOn() throws Exception {
        String t = token(validClaims());
        assertDoesNotThrow(() -> verifier.verify(t));
        clock.freezeAt(NOW.plusSeconds(3601));
        assertRejected(t);
    }

    @Test void rejectsTokensIssuedInTheFutureBeyondTheLeeway() throws Exception {
        long n = NOW.getEpochSecond();
        String iss = "https://securetoken.google.com/" + PROJECT;
        assertRejected(token(claims(iss, PROJECT, n + 7200, n + 3600, "u")));
        assertDoesNotThrow(() -> verifier.verify(token(claims(iss, PROJECT, n + 7200, n + 30, "u")))); // clock skew is fine
    }

    @Test void rejectsABadSubject() throws Exception {
        long n = NOW.getEpochSecond();
        String iss = "https://securetoken.google.com/" + PROJECT;
        assertRejected(token(claims(iss, PROJECT, n + 60, n, "")));
        assertRejected(token(claims(iss, PROJECT, n + 60, n, "x".repeat(129))));
        assertRejected(token("{\"iss\":\"" + iss + "\",\"aud\":\"" + PROJECT + "\",\"exp\":" + (n + 60) + ",\"iat\":" + n + "}"));
    }

    @Test void rejectsMissingOrNonNumericTimes() throws Exception {
        String iss = "https://securetoken.google.com/" + PROJECT;
        assertRejected(token("{\"iss\":\"" + iss + "\",\"aud\":\"" + PROJECT + "\",\"sub\":\"u\",\"iat\":1}"));
        assertRejected(token("{\"iss\":\"" + iss + "\",\"aud\":\"" + PROJECT + "\",\"sub\":\"u\",\"exp\":\"later\",\"iat\":1}"));
    }

    @Test void garbageIsA401NeverA500() {
        for (String junk : new String[] {null, "", " ", "abc", "a.b", "a.b.c", "...", "a..c", "!!!.@@@.###", "eyJhbGciOiJSUzI1NiJ9.e30.", "x".repeat(10_000)}) assertRejected(junk);
    }

    @Test void garbageHeaderAndPayloadAreRejected() {
        assertRejected(b64("not json") + "." + b64("{}") + ".c2ln");
        assertRejected(b64("[]") + "." + b64("{}") + ".c2ln");
        assertRejected(b64("\"RS256\"") + "." + b64("{}") + ".c2ln");
    }

    @Test void parsesGoogleStyleKeySets() throws Exception {
        var rsa = (java.security.interfaces.RSAPublicKey) good.getPublic();
        var enc = Base64.getUrlEncoder().withoutPadding();
        String n = enc.encodeToString(strip(rsa.getModulus().toByteArray()));
        String e = enc.encodeToString(strip(rsa.getPublicExponent().toByteArray()));
        String jwks = "{\"keys\":[{\"kid\":\"k1\",\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"n\":\"" + n + "\",\"e\":\"" + e + "\"},"
            + "{\"kid\":\"ec\",\"kty\":\"EC\"},{\"kty\":\"RSA\",\"n\":\"AQAB\",\"e\":\"AQAB\"},{\"kid\":\"bad\",\"kty\":\"RSA\",\"n\":\"%%%\",\"e\":\"AQAB\"}]}";
        Map<String, PublicKey> keys = GoogleJwksSource.parse(jwks);
        assertEquals(1, keys.size(), "EC, kid-less and malformed entries are skipped");
        FirebaseTokenVerifier v = new FirebaseTokenVerifier(PROJECT, kid -> Optional.ofNullable(keys.get(kid)), clock);
        assertEquals("uid-123", v.verify(token(validClaims())).uid());
        assertTrue(GoogleJwksSource.parse("{}").isEmpty());
    }

    static byte[] strip(byte[] b) { return b.length > 1 && b[0] == 0 ? java.util.Arrays.copyOfRange(b, 1, b.length) : b; }

    @Test void devVerifierOnlyAcceptsDevTokens() {
        DevTokenVerifier dev = new DevTokenVerifier();
        assertEquals("dev:u_me", dev.verify("dev:u_me").uid());
        for (String bad : new String[] {null, "", "dev:", "dev:has space", "Bearer dev:u_me", "dev:../x", "eyJhbGciOiJSUzI1NiJ9.e30.x"})
            assertEquals(401, assertThrows(ApiException.class, () -> dev.verify(bad)).status());
    }
}
