package app.trainmate.trip;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.trainmate.TestDb;
import app.trainmate.auth.AuthUser;
import app.trainmate.db.Db;
import app.trainmate.dev.MutableClock;
import app.trainmate.http.ApiException;
import app.trainmate.http.Body;
import app.trainmate.train.TrainRepository;
import app.trainmate.trip.TripDtos.TripDto;
import app.trainmate.user.UserRepository;
import app.trainmate.user.UserRepository.UserRow;
import app.trainmate.user.UserService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserTripServiceTest {
    Db db;
    UserRepository users;
    UserService userService;
    TripRepository tripRepo;
    TripService trips;
    UserRow asha;

    @BeforeEach void setUp() throws Exception {
        assumeTrue(TestDb.available(), "No PostgreSQL: skipping");
        db = TestDb.migratedDb();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-25T03:36:00Z"));
        clock.freezeAt(Instant.parse("2026-10-25T03:36:00Z"));
        users = new UserRepository(db);
        userService = new UserService(users);
        tripRepo = new TripRepository(db);
        trips = new TripService(tripRepo, new TrainRepository(db), clock, "test-salt");
        asha = userService.resolve(new AuthUser("firebase-asha", "asha@example.com", "Asha Kumar"));
    }

    static Body body(String json) { return Body.parse(json.getBytes(StandardCharsets.UTF_8)); }

    static String trip(String overrides) {
        String base = "{\"trainNo\":\"12345\",\"journeyDate\":\"2026-10-25\",\"boardingStn\":\"GHY\",\"destStn\":\"NDLS\",\"travelClass\":\"3A\",\"coach\":\"b3\",\"seatNo\":34,\"berthType\":\"UPPER\"}";
        if (overrides.isEmpty()) return base;
        var m = new java.util.LinkedHashMap<String, String>();
        for (String kv : base.substring(1, base.length() - 1).split(",(?=\")")) m.put(kv.split(":")[0], kv.substring(kv.indexOf(':') + 1));
        for (String kv : overrides.split(";")) { String[] p = kv.split("=", 2); if (p[1].equals("-")) m.remove("\"" + p[0] + "\""); else m.put("\"" + p[0] + "\"", p[1]); }
        return "{" + String.join(",", m.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).toList()) + "}";
    }

    void assertBad(String overrides, String code) {
        ApiException e = assertThrows(ApiException.class, () -> trips.create(asha.id(), body(trip(overrides))));
        assertEquals(code, e.code(), e.getMessage());
    }

    // ---- users ---------------------------------------------------------------------------------

    @Test void firstSignInCreatesAPrivacyFriendlyProfile() {
        assertEquals("Asha K.", asha.displayName());
        assertEquals("asha@example.com", asha.email());
        assertEquals(0, asha.trustScore());
        assertFalse(asha.blocked());
    }

    @Test void signingInAgainFindsTheSamePersonAndKeepsTheirName() {
        users.rename(asha.id(), "Asha");
        UserRow again = userService.resolve(new AuthUser("firebase-asha", "asha@example.com", "Completely Different Name"));
        assertEquals(asha.id(), again.id());
        assertEquals("Asha", again.displayName(), "a later sign-in must not overwrite a name they chose");
    }

    @Test void aMissingEmailOnALaterSignInDoesNotEraseTheStoredOne() {
        UserRow again = userService.resolve(new AuthUser("firebase-asha", null, null));
        assertEquals("asha@example.com", again.email());
    }

    @Test void simultaneousFirstRequestsCreateOnlyOneAccount() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<UUID>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) fs.add(pool.submit(() -> { go.await(); return userService.resolve(new AuthUser("new-person", "n@x.com", "New Person")).id(); }));
        go.countDown();
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (Future<UUID> f : fs) ids.add(f.get());
        pool.shutdown();
        assertEquals(1, ids.size());
        assertEquals(1, TestDb.count(db, "SELECT count(*) FROM users WHERE firebase_uid = 'new-person'"));
    }

    @Test void blockedAccountsAreRefused() {
        db.update("UPDATE users SET is_blocked = TRUE WHERE id = ?", asha.id());
        ApiException e = assertThrows(ApiException.class, () -> userService.resolve(new AuthUser("firebase-asha", null, null)));
        assertEquals(403, e.status());
        assertEquals("ACCOUNT_BLOCKED", e.code());
    }

    @Test void renameValidates() {
        assertEquals("Ash", userService.rename(asha, body("{\"displayName\":\"  Ash \"}")).displayName());
        assertEquals("LINKS_NOT_ALLOWED", assertThrows(ApiException.class, () -> userService.rename(asha, body("{\"displayName\":\"see www.x.com\"}"))).code());
        assertEquals(400, assertThrows(ApiException.class, () -> userService.rename(asha, body("{\"displayName\":\"\"}"))).status());
        assertEquals(400, assertThrows(ApiException.class, () -> userService.rename(asha, body("{\"displayName\":\"" + "x".repeat(41) + "\"}"))).status());
        assertEquals(400, assertThrows(ApiException.class, () -> userService.rename(asha, body("{}"))).status());
    }

    @Test void deletingAnAccountRemovesTheirTrips() {
        trips.create(asha.id(), body(trip("")));
        userService.delete(asha);
        assertEquals(0, TestDb.count(db, "SELECT count(*) FROM trips"));
        assertNull(users.byId(asha.id()));
    }

    // ---- trips ---------------------------------------------------------------------------------

    @Test void createsATripWithNamesFilledIn() {
        TripDto t = trips.create(asha.id(), body(trip("")));
        assertEquals("12345", t.trainNo());
        assertEquals("Express", t.trainName());
        assertEquals("2026-10-25", t.journeyDate());
        assertEquals("Guwahati", t.boardingStn().name());
        assertEquals("New Delhi", t.destStn().name());
        assertEquals("B3", t.coach(), "coach is upper-cased");
        assertEquals(34, t.seatNo());
        assertEquals("UPPER", t.berthType());
        assertTrue(t.createdAt().endsWith("+05:30"));
        assertDoesNotThrow(() -> UUID.fromString(t.id()));
    }

    @Test void optionalFieldsCanBeLeftOut() {
        TripDto t = trips.create(asha.id(), body(trip("seatNo=-;berthType=-")));
        assertNull(t.seatNo());
        assertNull(t.berthType());
    }

    @Test void thePnrIsHashedAndNeverReturned() {
        TripDto t = trips.create(asha.id(), body(trip("pnr=\"1234567890\"")));
        assertFalse(app.trainmate.util.Json.writeString(t).contains("1234567890"));
        String stored = db.one("SELECT pnr_hash FROM trips WHERE id = ?::uuid", rs -> rs.getString(1), t.id());
        assertEquals(64, stored.length());
        assertFalse(stored.contains("1234567890"));
        assertTrue(stored.matches("[0-9a-f]{64}"));
    }

    @Test void rejectsBadInput() {
        assertBad("trainNo=\"00000\"", "TRAIN_NOT_FOUND");
        assertBad("boardingStn=\"NDLS\";destStn=\"GHY\"", "STATION_ORDER");
        assertBad("boardingStn=\"GHY\";destStn=\"GHY\"", "STATION_ORDER");
        assertBad("destStn=\"XXX\"", "STATION_NOT_ON_ROUTE");
        assertBad("coach=\"BOGIE\"", "VALIDATION_ERROR");
        assertBad("coach=\"3\"", "VALIDATION_ERROR");
        assertBad("travelClass=\"9Z\"", "VALIDATION_ERROR");
        assertBad("berthType=\"ROOF\"", "VALIDATION_ERROR");
        assertBad("seatNo=0", "VALIDATION_ERROR");
        assertBad("seatNo=201", "VALIDATION_ERROR");
        assertBad("seatNo=\"34\"", "VALIDATION_ERROR");
        assertBad("pnr=\"12\"", "VALIDATION_ERROR");
        assertBad("pnr=\"12345abcde\"", "VALIDATION_ERROR");
        assertBad("journeyDate=\"2026-10-23\"", "VALIDATION_ERROR");
        assertBad("journeyDate=\"2027-06-01\"", "VALIDATION_ERROR");
        assertBad("journeyDate=\"tomorrow\"", "VALIDATION_ERROR");
        assertBad("journeyDate=-", "VALIDATION_ERROR");
        assertBad("trainNo=-", "VALIDATION_ERROR");
        assertThrows(ApiException.class, () -> trips.create(asha.id(), body("not json")));
    }

    @Test void theJourneyDateWindowIsYesterdayToFourMonthsAhead() {
        assertDoesNotThrow(() -> trips.create(asha.id(), body(trip("journeyDate=\"2026-10-24\""))));
        assertDoesNotThrow(() -> trips.create(asha.id(), body(trip("trainNo=\"12346\";journeyDate=\"2027-02-22\""))));
        assertBad("trainNo=\"12347\";journeyDate=\"2027-02-23\"", "VALIDATION_ERROR");
    }

    @Test void oneTripPerTrainPerDay() {
        trips.create(asha.id(), body(trip("")));
        ApiException e = assertThrows(ApiException.class, () -> trips.create(asha.id(), body(trip(""))));
        assertEquals(409, e.status());
        assertEquals("TRIP_EXISTS", e.code());
        assertDoesNotThrow(() -> trips.create(asha.id(), body(trip("journeyDate=\"2026-10-26\""))), "another day is fine");
        UserRow other = userService.resolve(new AuthUser("someone-else", null, "Other Person"));
        assertDoesNotThrow(() -> trips.create(other.id(), body(trip(""))), "another person is fine");
    }

    @Test void listsMyTripsNewestJourneyFirstAndNobodyElses() {
        trips.create(asha.id(), body(trip("")));
        trips.create(asha.id(), body(trip("trainNo=\"12346\";journeyDate=\"2026-10-27\"")));
        UserRow other = userService.resolve(new AuthUser("someone-else", null, "Other Person"));
        trips.create(other.id(), body(trip("")));
        List<TripDto> mine = trips.mine(asha.id());
        assertEquals(List.of("2026-10-27", "2026-10-25"), mine.stream().map(TripDto::journeyDate).toList());
        assertEquals(1, trips.mine(other.id()).size());
    }

    @Test void deletingATripIsOwnerOnlyAndReportsNotFound() {
        TripDto t = trips.create(asha.id(), body(trip("")));
        UserRow other = userService.resolve(new AuthUser("someone-else", null, "Other Person"));
        assertEquals("TRIP_NOT_FOUND", assertThrows(ApiException.class, () -> trips.delete(other.id(), t.id())).code());
        assertEquals("TRIP_NOT_FOUND", assertThrows(ApiException.class, () -> trips.delete(asha.id(), "not-a-uuid")).code());
        assertEquals("TRIP_NOT_FOUND", assertThrows(ApiException.class, () -> trips.delete(asha.id(), UUID.randomUUID().toString())).code());
        assertEquals(1, trips.mine(asha.id()).size(), "none of those deleted anything");
        trips.delete(asha.id(), t.id());
        assertTrue(trips.mine(asha.id()).isEmpty());
    }

    @Test void theActiveTripIsTodaysElseTheNearest() {
        trips.create(asha.id(), body(trip("trainNo=\"12346\";journeyDate=\"2026-10-27\"")));
        trips.create(asha.id(), body(trip("journeyDate=\"2026-10-24\"")));
        assertEquals("2026-10-24", tripRepo.active(asha.id(), LocalDate.parse("2026-10-25")).journeyDate(), "yesterday is nearer than two days ahead");
        trips.create(asha.id(), body(trip("trainNo=\"12347\";journeyDate=\"2026-10-25\"")));
        assertEquals("12347", tripRepo.active(asha.id(), LocalDate.parse("2026-10-25")).trainNo(), "today wins");
        assertEquals("12346", tripRepo.active(asha.id(), LocalDate.parse("2026-10-28")).trainNo());
        assertNull(tripRepo.active(UUID.randomUUID(), LocalDate.parse("2026-10-25")));
    }

    @Test void anyoneOnTellsTheStatusServiceWhetherSomeoneIsTravelling() {
        assertFalse(tripRepo.anyoneOn("12345", LocalDate.parse("2026-10-25")));
        trips.create(asha.id(), body(trip("")));
        assertTrue(tripRepo.anyoneOn("12345", LocalDate.parse("2026-10-25")));
        assertFalse(tripRepo.anyoneOn("12345", LocalDate.parse("2026-10-26")));
    }
}
