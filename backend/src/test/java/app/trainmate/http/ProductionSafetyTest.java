package app.trainmate.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.trainmate.TestDb;
import app.trainmate.Wiring;
import app.trainmate.auth.JwksSource;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What must hold when the app runs for real: Firebase sign-in only, no dev backdoors, no leaked internals. */
class ProductionSafetyTest {
    static final String PROJECT = "train-mate-prod";
    static KeyPair key;
    ApiHandler prod;

    @BeforeAll static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        key = g.generateKeyPair();
    }

    Wiring.Settings prodSettings() { return new Wiring.Settings("firebase", PROJECT, "a-long-random-salt-value", null, "mock", 900, false); }

    @BeforeEach void setUp() throws Exception {
        assumeTrue(TestDb.available(), "No PostgreSQL: skipping");
        TestDb.migratedDb();
        JwksSource jwks = kid -> "k1".equals(kid) ? Optional.<PublicKey>of(key.getPublic()) : Optional.<PublicKey>empty();
        prod = Wiring.build(prodSettings(), TestDb.dataSource(), jwks).handler();
    }

    static String b64(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    String idToken(String uid, String name, String email, long expOffsetSeconds) throws Exception {
        long now = Instant.now().getEpochSecond();
        String claims = "{\"iss\":\"https://securetoken.google.com/" + PROJECT + "\",\"aud\":\"" + PROJECT + "\",\"exp\":" + (now + expOffsetSeconds)
            + ",\"iat\":" + (now - 5) + ",\"auth_time\":" + (now - 5) + ",\"sub\":\"" + uid + "\",\"name\":\"" + name + "\",\"email\":\"" + email + "\"}";
        String input = b64("{\"alg\":\"RS256\",\"kid\":\"k1\"}") + "." + b64(claims);
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key.getPrivate());
        s.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign());
    }

    Res call(ApiHandler h, String method, String path, String token) {
        Map<String, String> headers = token == null ? Map.of() : Map.of("authorization", "Bearer " + token);
        return h.handle(new Req(method, path, Map.of(), headers, new byte[0]));
    }

    // ---- sign-in -------------------------------------------------------------------------------------------

    @Test void aValidFirebaseTokenSignsInAndCreatesAPrivacyFriendlyProfile() throws Exception {
        Res r = call(prod, "GET", "/api/me", idToken("g-uid-1", "Asha Kumar", "asha@gmail.com", 3600));
        assertEquals(200, r.status());
        JsonNode me = app.trainmate.util.Json.tree(r.body());
        assertEquals("Asha K.", me.get("displayName").asText());
        assertEquals("asha@gmail.com", me.get("email").asText());
        String id = me.get("id").asText();
        JsonNode again = app.trainmate.util.Json.tree(call(prod, "GET", "/api/me", idToken("g-uid-1", "Someone Else", "asha@gmail.com", 3600)).body());
        assertEquals(id, again.get("id").asText(), "the same Google account is the same person");
    }

    @Test void devTokensAreRefusedInProduction() {
        for (String t : new String[] {"dev:u_me", "dev:anything"}) {
            Res r = call(prod, "GET", "/api/me", t);
            assertEquals(401, r.status(), "a dev token must never work when AUTH_MODE=firebase");
        }
    }

    @Test void forgedAndExpiredTokensAreRefused() throws Exception {
        String good = idToken("g-uid-2", "Priya S", "p@x.com", 3600);
        String[] p = good.split("\\.");
        String forged = p[0] + "." + b64(new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8).replace("g-uid-2", "g-uid-admin")) + "." + p[2];
        assertEquals(401, call(prod, "GET", "/api/me", forged).status());
        assertEquals(401, call(prod, "GET", "/api/me", idToken("g-uid-3", "Late", "l@x.com", -10)).status());
        assertEquals(401, call(prod, "GET", "/api/me", null).status());
    }

    @Test void productionDoesNotExposeTheDevEndpoints() {
        assertEquals(404, call(prod, "POST", "/api/dev/reset", null).status());
        assertEquals(404, call(prod, "POST", "/api/dev/clock", null).status());
    }

    @Test void publicEndpointsStillWorkWithoutASignIn() {
        assertEquals(200, call(prod, "GET", "/api/health", null).status());
        assertEquals(200, call(prod, "GET", "/api/trains/12345/route", null).status());
    }

    // ---- refusing to start unsafely --------------------------------------------------------------------------------

    @Test void refusesUnsafeConfiguration() {
        DataSource ds = TestDb.dataSource();
        var bad = new Wiring.Settings[] {
            new Wiring.Settings("dev", null, "salt-salt-salt-salt", null, "mock", 900, false),         // dev sign-in without dev tools
            new Wiring.Settings("firebase", "", "salt-salt-salt-salt", null, "mock", 900, false),      // no Firebase project
            new Wiring.Settings("firebase", null, "salt-salt-salt-salt", null, "mock", 900, false),
            new Wiring.Settings("firebase", PROJECT, "", null, "mock", 900, false),                     // no PNR salt
            new Wiring.Settings("firebase", PROJECT, "short", null, "mock", 900, false),
            new Wiring.Settings("magic", PROJECT, "salt-salt-salt-salt", null, "mock", 900, false),    // unknown auth mode
            new Wiring.Settings("firebase", PROJECT, "salt-salt-salt-salt", null, "irctc", 900, false) // provider that doesn't exist yet
        };
        for (Wiring.Settings s : bad) assertThrows(IllegalStateException.class, () -> Wiring.build(s, ds), s.toString());
        assertDoesNotThrow(() -> Wiring.build(prodSettings(), ds));
    }

    @Test void readsItsSettingsFromTheEnvironment() {
        var s = Wiring.Settings.fromEnv(Map.of("AUTH_MODE", "firebase", "FIREBASE_PROJECT_ID", "p", "PNR_SALT", "x".repeat(20), "REDIS_URL", "rediss://u:p@h:6380", "PROVIDER_DAILY_BUDGET", "50", "DEV_TOOLS", "1"));
        assertEquals("firebase", s.authMode());
        assertEquals("p", s.firebaseProjectId());
        assertEquals(50, s.dailyProviderBudget());
        assertTrue(s.devTools());
        var defaults = Wiring.Settings.fromEnv(Map.of());
        assertEquals("firebase", defaults.authMode(), "safe by default: Firebase, not dev");
        assertFalse(defaults.devTools());
        assertEquals("mock", defaults.provider());
    }

    // ---- never leak internals ---------------------------------------------------------------------------------------

    @Test void aDatabaseFailureIsAGenericFiveHundred() {
        DataSource broken = new DataSource() {
            public Connection getConnection() throws SQLException { throw new SQLException("connection refused to db.internal.example:5432 password=hunter2"); }
            public Connection getConnection(String u, String p) throws SQLException { return getConnection(); }
            public java.io.PrintWriter getLogWriter() { return null; }
            public void setLogWriter(java.io.PrintWriter out) {}
            public void setLoginTimeout(int s) {}
            public int getLoginTimeout() { return 0; }
            public java.util.logging.Logger getParentLogger() { return null; }
            public <T> T unwrap(Class<T> c) { throw new UnsupportedOperationException(); }
            public boolean isWrapperFor(Class<?> c) { return false; }
        };
        ApiHandler h = Wiring.build(new Wiring.Settings("dev", null, "", null, "mock", 900, true), broken).handler();
        Res r = h.handle(new Req("GET", "/api/me", Map.of(), Map.of("authorization", "Bearer dev:u_me"), new byte[0]));
        String body = new String(r.body(), StandardCharsets.UTF_8);
        assertEquals(500, r.status());
        assertTrue(body.contains("INTERNAL_ERROR"));
        assertFalse(body.contains("hunter2") || body.contains("db.internal") || body.contains("SQL") || body.contains("Exception"), body);
    }
}
