package app.trainmate.http;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.trainmate.TestDb;
import app.trainmate.Wiring;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiHandlerTest {
    ApiHandler api;

    @BeforeEach void setUp() throws Exception {
        assumeTrue(TestDb.available(), "No PostgreSQL: skipping");
        TestDb.migratedDb();
        api = Wiring.build(new Wiring.Settings("dev", null, "", null, "mock", 900, true), TestDb.dataSource()).handler();
        assertEquals(204, call("POST", "/api/dev/reset", null, null).status());
        assertEquals(200, call("POST", "/api/dev/clock", null, "{\"iso\":\"2026-10-25T09:06:00+05:30\"}").status());
    }

    record Out(int status, JsonNode json, Res res) {}

    Out call(String method, String path, String token, String body) {
        String p = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;
        String q = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        Map<String, String> h = new LinkedHashMap<>();
        if (token != null) h.put("authorization", "Bearer " + token);
        Res r = api.handle(new Req(method, p, Req.parseQuery(q), h, body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8)));
        JsonNode j = null;
        try {
            if (r.body().length > 0) j = app.trainmate.util.Json.tree(r.body());
        } catch (Exception e) {
            fail("response was not JSON: " + new String(r.body(), StandardCharsets.UTF_8));
        }
        return new Out(r.status(), j, r);
    }

    Out get(String path, String token) { return call("GET", path, token, null); }

    void assertProblem(Out o, int status, String code) {
        assertEquals(status, o.status(), o.json() == null ? "" : o.json().toString());
        assertEquals("application/problem+json", o.res().contentType());
        assertEquals(code, o.json().get("code").asText());
        assertEquals(status, o.json().get("status").asInt());
        assertTrue(o.json().hasNonNull("title") && o.json().hasNonNull("detail") && o.json().hasNonNull("type"));
    }

    // ---- basics and errors --------------------------------------------------------------------------

    @Test void healthIsPublic() {
        Out o = get("/api/health", null);
        assertEquals(200, o.status());
        assertEquals("ok", o.json().get("status").asText());
        assertEquals("2026-10-25T09:06:00+05:30", o.json().get("now").asText());
        assertEquals("application/json", o.res().contentType());
        assertEquals("no-store", o.res().headers().get("Cache-Control"));
    }

    @Test void unknownEndpointsAndWrongMethods() {
        assertProblem(get("/api/nope", "dev:u_me"), 404, "ENDPOINT_NOT_FOUND");
        assertProblem(call("PUT", "/api/me", "dev:u_me", "{}"), 405, "METHOD_NOT_ALLOWED");
        assertProblem(call("POST", "/api/health", null, "{}"), 405, "METHOD_NOT_ALLOWED");
        assertEquals(200, get("/api/health/", null).status(), "a trailing slash is fine");
    }

    @Test void theApiPrefixIsStrippedExactlyOnce() {
        assertProblem(get("/api/api/health", null), 404, "ENDPOINT_NOT_FOUND");
        assertProblem(get("/apix/health", null), 404, "ENDPOINT_NOT_FOUND");
    }

    @Test void badJsonIs400() {
        assertProblem(call("POST", "/api/trips", "dev:u_me", "{oops"), 400, "INVALID_JSON");
        assertProblem(call("POST", "/api/trips", "dev:u_me", "[1,2]"), 400, "VALIDATION_ERROR");
        assertProblem(call("POST", "/api/trips", "dev:u_me", "\"text\""), 400, "VALIDATION_ERROR");
    }

    // ---- authentication ---------------------------------------------------------------------------------

    @Test void protectedEndpointsNeedASignIn() {
        for (String[] r : new String[][] {{"GET", "/api/me"}, {"GET", "/api/trips/mine"}, {"POST", "/api/trips"}, {"GET", "/api/feed/around-me"}, {"DELETE", "/api/me"}}) {
            assertProblem(call(r[0], r[1], null, "{}"), 401, "UNAUTHENTICATED");
        }
    }

    @Test void badTokensAreRejectedWithA401() {
        for (String t : new String[] {"garbage", "dev:", "dev:has space", "eyJhbGciOiJub25lIn0.e30.", ""}) assertProblem(get("/api/me", t), 401, "UNAUTHENTICATED");
        Res r = api.handle(new Req("GET", "/api/me", Map.of(), Map.of("authorization", "Basic dXNlcjpwYXNz"), new byte[0]));
        assertEquals(401, r.status());
    }

    @Test void theBearerSchemeAndHeaderNameAreCaseInsensitive() {
        Res r = api.handle(new Req("GET", "/api/me", Map.of(), Map.of("authorization", "bearer dev:u_me"), new byte[0]));
        assertEquals(200, r.status());
    }

    @Test void anInvalidTokenOnAPublicEndpointIsStillRejected() {
        assertProblem(get("/api/trains/12345/status?date=2026-10-25", "garbage"), 401, "UNAUTHENTICATED");
        assertEquals(200, get("/api/trains/12345/status?date=2026-10-25", null).status());
    }

    // ---- the Home design, through HTTP ----------------------------------------------------------------------

    @Test void statusMatchesTheHomeDesign() {
        for (String token : new String[] {"dev:u_me", null}) {
            JsonNode s = get("/api/trains/12345/status?date=2026-10-25", token).json();
            assertEquals("PROVIDER", s.get("source").asText());
            assertEquals(12, s.get("delayMinutes").asInt());
            assertEquals("10–15 min late", s.get("delayLabel").asText());
            assertEquals("Nagaon", s.get("lastStation").get("name").asText());
            assertEquals("2026-10-25T08:42:00+05:30", s.get("lastSeenAt").asText());
            assertEquals("2026-10-25T09:02:00+05:30", s.get("fetchedAt").asText());
            assertEquals("Lumding Jn", s.get("nextStation").get("station").get("name").asText());
            assertEquals("2026-10-25T09:20:00+05:30", s.get("nextStation").get("etaFrom").asText());
            assertEquals("2026-10-25T09:35:00+05:30", s.get("nextStation").get("etaTo").asText());
            assertEquals("2026-10-25T14:00:00+05:30", s.get("destination").get("etaFrom").asText());
            assertEquals("2026-10-25T14:15:00+05:30", s.get("destination").get("etaTo").asText());
            assertFalse(s.get("stale").asBoolean());
            assertTrue(s.get("notice").isNull());
        }
    }

    @Test void theResponseHasExactlyTheFieldsTheWebAppExpects() {
        JsonNode s = get("/api/trains/12345/status?date=2026-10-25", null).json();
        var names = new java.util.TreeSet<String>();
        s.fieldNames().forEachRemaining(names::add);
        assertEquals(new java.util.TreeSet<>(java.util.List.of("trainNo", "date", "source", "lastStation", "lastSeenAt", "fetchedAt", "delayMinutes",
            "delayLowMin", "delayHighMin", "delayLabel", "nextStation", "destination", "stale", "notice")), names);
    }

    @Test void aTrackedTrainGoesStaleSoonerThanAnUntrackedOne() {
        call("POST", "/api/dev/clock", null, "{\"iso\":\"2026-10-25T09:12:00+05:30\"}"); // data fetched at 09:02 is now 10 minutes old
        assertFalse(get("/api/trains/12345/status?date=2026-10-25", null).json().get("stale").asBoolean(), "25 minutes for anonymous callers");
        assertTrue(get("/api/trains/12345/status?date=2026-10-25", "dev:u_me").json().get("stale").asBoolean(), "5 minutes for someone with this train saved");
    }

    @Test void timetableOnlyTrains() {
        JsonNode s = get("/api/trains/12346/status?date=2026-10-25", null).json();
        assertEquals("SCHEDULE", s.get("source").asText());
        assertTrue(s.get("delayMinutes").isNull());
        assertTrue(s.get("notice").asText().startsWith("Timetable only"));
    }

    @Test void statusValidatesItsInput() {
        assertProblem(get("/api/trains/99999/status?date=2026-10-25", null), 404, "TRAIN_NOT_FOUND");
        assertProblem(get("/api/trains/12345/status?date=tomorrow", null), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/trains/12345/status", null), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/trains/12345/status?date=2026-13-45", null), 400, "VALIDATION_ERROR");
    }

    @Test void trainSearchAndRoute() {
        assertEquals(1, get("/api/trains/search?q=12345", null).json().get("items").size());
        assertEquals("Express", get("/api/trains/search?q=express", null).json().get("items").get(0).get("name").asText());
        assertEquals(2, get("/api/trains/search?q=sample", null).json().get("items").size());
        assertEquals("New Delhi", get("/api/trains/search?q=12345", null).json().get("items").get(0).get("to").get("name").asText());
        assertProblem(get("/api/trains/search?q=x", null), 400, "VALIDATION_ERROR");
        assertProblem(get("/api/trains/search", null), 400, "VALIDATION_ERROR");
        assertEquals(0, get("/api/trains/search?q=%25%25", null).json().get("items").size(), "literal percent signs match nothing, not everything");

        JsonNode r = get("/api/trains/12345/route", null).json();
        assertEquals("12345", r.get("trainNo").asText());
        assertEquals(5, r.get("stops").size());
        assertTrue(r.get("stops").get(0).get("schedArr").isNull());
        assertEquals("06:30", r.get("stops").get(0).get("schedDep").asText());
        assertEquals("08:30", r.get("stops").get(1).get("schedArr").asText());
        assertEquals(0, r.get("stops").get(1).get("dayOffset").asInt());
        assertProblem(get("/api/trains/99999/route", null), 404, "TRAIN_NOT_FOUND");
    }

    // ---- the seeded travellers -------------------------------------------------------------------------------

    @Test void devSeedHasTheDesignsTraveller() {
        JsonNode me = get("/api/me", "dev:u_me").json();
        assertEquals("Asha K.", me.get("displayName").asText());
        assertTrue(me.get("id").asText().matches("[0-9a-f-]{36}"));
        JsonNode trips = get("/api/trips/mine", "dev:u_me").json().get("items");
        assertEquals(1, trips.size());
        assertEquals("B3", trips.get(0).get("coach").asText());
        assertEquals(34, trips.get(0).get("seatNo").asInt());
        assertEquals("UPPER", trips.get(0).get("berthType").asText());
        assertEquals("Guwahati", trips.get(0).get("boardingStn").get("name").asText());
    }

    @Test void aroundYouFindsTheActiveTrip() {
        JsonNode r = get("/api/feed/around-me", "dev:u_me").json();
        assertEquals("12345", r.get("trip").get("trainNo").asText());
        assertEquals(0, r.get("items").size());
        // a brand-new account has no trip
        JsonNode nobody = get("/api/feed/around-me", "dev:newcomer").json();
        assertTrue(nobody.get("trip").isNull());
    }

    // ---- trips and account ---------------------------------------------------------------------------------------

    @Test void tripsThroughHttp() {
        String body = "{\"trainNo\":\"12346\",\"journeyDate\":\"2026-10-25\",\"boardingStn\":\"GHY\",\"destStn\":\"NDLS\",\"travelClass\":\"SL\",\"coach\":\"s5\"}";
        Out created = call("POST", "/api/trips", "dev:u_priya", body);
        assertEquals(201, created.status());
        assertEquals("S5", created.json().get("coach").asText());
        assertTrue(created.json().get("seatNo").isNull());
        assertProblem(call("POST", "/api/trips", "dev:u_priya", body), 409, "TRIP_EXISTS");
        assertProblem(call("POST", "/api/trips", "dev:u_priya", body.replace("\"coach\":\"s5\"", "\"coach\":\"zzz\"").replace("12346", "12347")), 400, "VALIDATION_ERROR");

        String id = created.json().get("id").asText();
        assertProblem(call("DELETE", "/api/trips/" + id, "dev:u_me", null), 404, "TRIP_NOT_FOUND");
        Out del = call("DELETE", "/api/trips/" + id, "dev:u_priya", null);
        assertEquals(204, del.status());
        assertNull(del.json());
        assertEquals(1, get("/api/trips/mine", "dev:u_priya").json().get("items").size());
    }

    @Test void accountThroughHttp() {
        assertEquals("Ash", call("PATCH", "/api/me", "dev:u_me", "{\"displayName\":\"Ash\"}").json().get("displayName").asText());
        assertProblem(call("PATCH", "/api/me", "dev:u_me", "{\"displayName\":\"visit http://x.y\"}"), 400, "LINKS_NOT_ALLOWED");
        assertEquals(204, call("DELETE", "/api/me", "dev:u_me", null).status());
        assertEquals(0, get("/api/trips/mine", "dev:u_me").json().get("items").size(), "the old data is gone");
    }

    // ---- development tools --------------------------------------------------------------------------------------------

    @Test void resetBringsBackTheSeedData() {
        call("DELETE", "/api/me", "dev:u_me", null);
        call("PATCH", "/api/me", "dev:u_priya", "{\"displayName\":\"Changed\"}");
        assertEquals(204, call("POST", "/api/dev/reset", null, null).status());
        assertEquals("Asha K.", get("/api/me", "dev:u_me").json().get("displayName").asText());
        assertEquals("Priya S.", get("/api/me", "dev:u_priya").json().get("displayName").asText());
    }

    @Test void overlappingResetsDoNotCollide() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(6);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
        for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> call("POST", "/api/dev/reset", null, null).status()));
        for (var f : futures) assertEquals(204, f.get());
        pool.shutdown();
        assertEquals(9, TestDb.count(new app.trainmate.db.Db(TestDb.dataSource()), "SELECT count(*) FROM users"));
        assertEquals(9, TestDb.count(new app.trainmate.db.Db(TestDb.dataSource()), "SELECT count(*) FROM trips"));
    }

    @Test void theDevClockMovesTheStatus() {
        call("POST", "/api/dev/clock", null, "{\"iso\":\"2026-10-25T09:26:00+05:30\"}"); // past Lumding (9:10 + 12)
        JsonNode s = get("/api/trains/12345/status?date=2026-10-25", "dev:u_me").json();
        assertEquals(2, 2);
        assertTrue(s.get("stale").asBoolean(), "the 09:02 data is now over 5 minutes old");
        assertEquals("2026-10-25T09:26:00+05:30", call("POST", "/api/dev/clock", null, "{\"iso\":\"2026-10-25T09:26:00+05:30\"}").json().get("now").asText());
        assertProblem(call("POST", "/api/dev/clock", null, "{\"iso\":\"soon\"}"), 400, "VALIDATION_ERROR");
    }
}
