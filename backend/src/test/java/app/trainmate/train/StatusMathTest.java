package app.trainmate.train;

import static org.junit.jupiter.api.Assertions.*;

import app.trainmate.train.Dtos.TrainStatusDto;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatusMathTest {
    static final LocalDate DATE = LocalDate.parse("2026-10-25");

    static RouteStop stop(int seq, String code, String name, String arr, String dep, int dayOffset) {
        return new RouteStop(seq, code, name, arr == null ? null : LocalTime.parse(arr), dep == null ? null : LocalTime.parse(dep), dayOffset);
    }

    static final List<RouteStop> ROUTE = List.of(
        stop(1, "GHY", "Guwahati", null, "06:30", 0), stop(2, "NGC", "Nagaon", "08:30", "08:32", 0),
        stop(3, "LMG", "Lumding Jn", "09:10", "09:15", 0), stop(4, "NJP", "New Jalpaiguri", "11:50", "12:05", 0),
        stop(5, "NDLS", "New Delhi", "13:50", null, 0));

    static CachedStatus cached(String station, int delay, String observed, String fetched) {
        return new CachedStatus(station, delay, Instant.parse(observed).toEpochMilli(), Instant.parse(fetched).toEpochMilli(), "mock");
    }

    static TrainStatusDto build(CachedStatus c, boolean stale, String now) { return StatusMath.build("12345", DATE, ROUTE, c, stale, Instant.parse(now)); }

    // 09:06 IST is 03:36 UTC; 08:42 IST is 03:12 UTC; 09:02 IST is 03:32 UTC.
    static final CachedStatus DESIGN = cached("NGC", 12, "2026-10-25T03:12:00Z", "2026-10-25T03:32:00Z");

    @Test void reproducesTheHomeDesign() {
        TrainStatusDto s = build(DESIGN, false, "2026-10-25T03:36:00Z");
        assertEquals("PROVIDER", s.source());
        assertEquals(12, s.delayMinutes());
        assertEquals("10–15 min late", s.delayLabel());
        assertEquals(10, s.delayLowMin());
        assertEquals(15, s.delayHighMin());
        assertEquals("Nagaon", s.lastStation().name());
        assertEquals("2026-10-25T08:42:00+05:30", s.lastSeenAt());
        assertEquals("2026-10-25T09:02:00+05:30", s.fetchedAt());
        assertEquals("Lumding Jn", s.nextStation().station().name());
        assertEquals("2026-10-25T09:10:00+05:30", s.nextStation().scheduledAt());
        assertEquals("2026-10-25T09:20:00+05:30", s.nextStation().etaFrom());
        assertEquals("2026-10-25T09:35:00+05:30", s.nextStation().etaTo());
        assertEquals("2026-10-25T14:00:00+05:30", s.destination().etaFrom());
        assertEquals("2026-10-25T14:15:00+05:30", s.destination().etaTo());
        assertFalse(s.stale());
        assertNull(s.notice());
    }

    @Test void staleDataSaysItIsUpdating() {
        TrainStatusDto s = build(DESIGN, true, "2026-10-25T03:36:00Z");
        assertTrue(s.stale());
        assertEquals("Updating. This may be a few minutes out of date.", s.notice());
    }

    @Test void theRangeWidensAsTheDataAges() {
        // age under 10 min: r=3 -> 9..15 -> 10..15
        assertEquals("10–15 min late", build(DESIGN, false, "2026-10-25T03:36:00Z").delayLabel());
        // age 10 min: r=5 -> 7..17 -> 5..15
        assertEquals("5–15 min late", build(DESIGN, false, "2026-10-25T03:42:00Z").delayLabel());
        // age 30 min is still r=5
        assertEquals("5–15 min late", build(DESIGN, false, "2026-10-25T04:02:00Z").delayLabel());
        // older than 30 min: r=10 -> 2..22 -> 0..20. A train that is 12 minutes late must not read "on time".
        TrainStatusDto old = build(DESIGN, true, "2026-10-25T04:03:00Z");
        assertEquals(0, old.delayLowMin());
        assertEquals(20, old.delayHighMin());
        assertEquals("up to 20 min late", old.delayLabel());
    }

    @Test void roundsToFiveMinutes() {
        assertEquals(0, StatusMath.round5(2.4));
        assertEquals(5, StatusMath.round5(2.5));
        assertEquals(10, StatusMath.round5(9));
        assertEquals(15, StatusMath.round5(15));
        assertEquals(-5, StatusMath.round5(-3));
        assertEquals(0, StatusMath.round5(-2.5)); // half rounds up, like JavaScript's Math.round the web mock uses
    }

    @Test void labelsEarlyAndOnTime() {
        assertEquals("On time", StatusMath.delayLabel(0, -5, 5));
        assertEquals("5–10 min early", StatusMath.delayLabel(-7, -10, -5));
        assertEquals("10–15 min late", StatusMath.delayLabel(12, 10, 15));
        assertEquals("up to 5 min late", StatusMath.delayLabel(2, 0, 5));
        assertEquals("up to 5 min early", StatusMath.delayLabel(-2, -5, 0));
        assertEquals("up to 20 min late", StatusMath.delayLabel(12, 0, 20));
        TrainStatusDto early = build(cached("NGC", -7, "2026-10-25T03:00:00Z", "2026-10-25T03:32:00Z"), false, "2026-10-25T03:36:00Z");
        assertEquals("5–10 min early", early.delayLabel());
        assertEquals("2026-10-25T09:00:00+05:30", early.nextStation().etaFrom(), "an early train can arrive before its scheduled time");
        TrainStatusDto onTime = build(cached("NGC", 0, "2026-10-25T03:00:00Z", "2026-10-25T03:32:00Z"), false, "2026-10-25T03:36:00Z");
        assertEquals("On time", onTime.delayLabel());
    }

    @Test void atTheLastStationTheTrainHasArrived() {
        TrainStatusDto s = build(cached("NDLS", 12, "2026-10-25T08:40:00Z", "2026-10-25T08:41:00Z"), false, "2026-10-25T08:42:00Z");
        assertNull(s.nextStation());
        assertEquals("Arrived at New Delhi.", s.notice());
        assertNotNull(s.destination());
    }

    @Test void withoutLiveDataItIsJustTheTimetable() {
        TrainStatusDto s = build(null, false, "2026-10-25T03:36:00Z"); // 09:06 IST
        assertEquals("SCHEDULE", s.source());
        assertNull(s.delayMinutes());
        assertNull(s.lastStation());
        assertEquals("Timetable only. Live position isn't available for this train.", s.notice());
        assertEquals("Lumding Jn", s.nextStation().station().name());
        assertEquals(s.nextStation().scheduledAt(), s.nextStation().etaFrom());
        assertEquals(s.nextStation().scheduledAt(), s.nextStation().etaTo());
        assertEquals("2026-10-25T13:50:00+05:30", s.destination().scheduledAt());
    }

    @Test void aStationNotOnTheRouteFallsBackToTheTimetable() {
        assertEquals("SCHEDULE", build(cached("XXX", 5, "2026-10-25T03:00:00Z", "2026-10-25T03:32:00Z"), false, "2026-10-25T03:36:00Z").source());
    }

    @Test void afterTheLastStopThereIsNoNextStation() {
        assertNull(build(null, false, "2026-10-26T03:36:00Z").nextStation());
    }

    @Test void overnightTrainsUseTheDayOffset() {
        List<RouteStop> night = List.of(stop(1, "AAA", "Start", null, "22:00", 0), stop(2, "BBB", "Middle", "23:50", "23:55", 0), stop(3, "CCC", "End", "01:10", null, 1));
        CachedStatus c = new CachedStatus("BBB", 10, Instant.parse("2026-10-25T18:30:00Z").toEpochMilli(), Instant.parse("2026-10-25T18:31:00Z").toEpochMilli(), "mock");
        TrainStatusDto s = StatusMath.build("99999", DATE, night, c, false, Instant.parse("2026-10-25T18:32:00Z"));
        assertEquals("2026-10-26T01:10:00+05:30", s.destination().scheduledAt());
        assertEquals("2026-10-26T01:15:00+05:30", s.destination().etaFrom());
        assertEquals("2026-10-26T01:35:00+05:30", s.destination().etaTo());
    }

    @Test void timestampsAlwaysCarryTheISTOffset() {
        TrainStatusDto s = build(DESIGN, false, "2026-10-25T03:36:00Z");
        for (String t : new String[] {s.lastSeenAt(), s.fetchedAt(), s.nextStation().etaFrom(), s.destination().etaTo()}) assertTrue(t.endsWith("+05:30"), t);
    }
}
