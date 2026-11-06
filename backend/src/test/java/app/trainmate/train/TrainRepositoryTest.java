package app.trainmate.train;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.trainmate.TestDb;
import app.trainmate.db.Db;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrainRepositoryTest {
    Db db;
    TrainRepository trains;

    @BeforeEach void setUp() throws Exception {
        assumeTrue(TestDb.available(), "No PostgreSQL: skipping");
        db = TestDb.migratedDb();
        trains = new TrainRepository(db);
    }

    @Test void searchesByNumberPrefixAndByName() {
        assertEquals(3, trains.search("123").size());
        assertEquals(List.of("12345"), trains.search("12345").stream().map(TrainRepository.TrainInfo::trainNo).toList());
        assertEquals(2, trains.search("sample").size());
        assertEquals(1, trains.search("Express").size());
        assertEquals(1, trains.search("EXPRESS").size(), "case-insensitive");
        assertEquals(1, trains.search("12345 exp").size(), "number and name together");
        assertEquals(0, trains.search("zzzz").size());
    }

    @Test void wildcardCharactersAreJustText() {
        assertEquals(0, trains.search("%").size(), "% must not match everything");
        assertEquals(0, trains.search("_").size());
        assertEquals(0, trains.search("'; DROP TABLE trains; --").size());
        assertEquals(3, trains.search("123").size(), "and the table is still there");
    }

    @Test void returnsTheRouteInOrderWithTimes() {
        List<RouteStop> r = trains.route("12345");
        assertEquals(List.of("GHY", "NGC", "LMG", "NJP", "NDLS"), r.stream().map(RouteStop::code).toList());
        assertEquals(List.of(1, 2, 3, 4, 5), r.stream().map(RouteStop::seq).toList());
        assertNull(r.get(0).arr());
        assertEquals("06:30", r.get(0).dep().toString());
        assertEquals("09:10", r.get(2).arr().toString());
        assertEquals("09:10", r.get(2).sched().toString());
        assertEquals("Lumding Jn", r.get(2).name());
        assertNull(r.get(4).dep());
        assertTrue(trains.route("99999").isEmpty());
    }

    @Test void findsTrainsWithTheirEndpoints() {
        var t = trains.find("12345");
        assertEquals("Express", t.name());
        assertEquals("Guwahati", t.from().name());
        assertEquals("New Delhi", t.to().name());
        assertNull(trains.find("00000"));
    }

    @Test void runDaysStartOnMonday() {
        var t = new TrainRepository.TrainInfo("1", "x", null, null, "M-W----");
        assertTrue(t.runsOn(LocalDate.parse("2026-10-26"))); // Monday
        assertFalse(t.runsOn(LocalDate.parse("2026-10-27"))); // Tuesday
        assertTrue(t.runsOn(LocalDate.parse("2026-10-28"))); // Wednesday
        assertFalse(t.runsOn(LocalDate.parse("2026-11-01"))); // Sunday
        assertTrue(new TrainRepository.TrainInfo("1", "x", null, null, null).runsOn(LocalDate.parse("2026-11-01")), "no run_days means daily");
    }

    @Test void importingTwiceChangesNothing() throws Exception {
        int before = TestDb.count(db, "SELECT count(*) FROM train_schedule");
        try (var in = getClass().getResourceAsStream("/fixtures/schedule.json")) {
            assertEquals(3, new ScheduleImporter(db).importJson(in));
        }
        assertEquals(before, TestDb.count(db, "SELECT count(*) FROM train_schedule"));
        assertEquals(3, TestDb.count(db, "SELECT count(*) FROM trains"));
    }

    @Test void importReplacesATrainsStopsAndRenames() throws Exception {
        String json = """
            {"stations":[{"code":"GHY","name":"Guwahati Jn"},{"code":"NDLS","name":"New Delhi"}],
             "trains":[{"trainNo":"12345","name":"Express Renamed","runDays":"M------","stops":[{"station":"GHY","dep":"07:00"},{"station":"NDLS","arr":"15:00"}]}]}""";
        new ScheduleImporter(db).importJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, trains.route("12345").size());
        assertEquals("Express Renamed", trains.find("12345").name());
        assertEquals("Guwahati Jn", trains.find("12345").from().name());
        assertEquals("M------", trains.find("12345").runDays());
    }

    @Test void aBadTrainInTheFileLeavesTheTimetableUntouched() {
        String json = """
            {"stations":[{"code":"GHY","name":"CHANGED"}],
             "trains":[{"trainNo":"12345","name":"Changed","stops":[{"station":"GHY","dep":"07:00"},{"station":"NDLS","arr":"15:00"}]},
                       {"trainNo":"99999","name":"Broken","stops":[{"station":"GHY","dep":"07:00"}]}]}""";
        assertThrows(IllegalArgumentException.class, () -> new ScheduleImporter(db).importJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))));
        assertEquals("Express", trains.find("12345").name(), "rolled back");
        assertEquals("Guwahati", trains.find("12345").from().name(), "even the station rename was rolled back");
        assertNull(trains.find("99999"));
        assertEquals(5, trains.route("12345").size());
    }
}
