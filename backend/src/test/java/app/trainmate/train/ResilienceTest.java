package app.trainmate.train;

import static org.junit.jupiter.api.Assertions.*;

import app.trainmate.dev.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResilienceTest {
    static final Instant NOW = Instant.parse("2026-10-25T03:36:00Z");
    static final LocalDate DATE = LocalDate.parse("2026-10-25");
    final MutableClock clock = new MutableClock(NOW);

    // ---- circuit breaker ----------------------------------------------------------------

    @Test void breakerOpensAfterConsecutiveFailuresAndProbesOnce() {
        CircuitBreaker b = new CircuitBreaker(clock, 3, Duration.ofSeconds(60));
        assertTrue(b.allow());
        b.failure(); b.failure();
        assertTrue(b.allow(), "two failures are not enough");
        b.failure();
        assertEquals(CircuitBreaker.State.OPEN, b.state());
        assertFalse(b.allow());
        clock.advance(Duration.ofSeconds(59));
        assertFalse(b.allow(), "still open");
        clock.advance(Duration.ofSeconds(2));
        assertTrue(b.allow(), "one probe after the open period");
        assertFalse(b.allow(), "only one probe at a time");
        b.success();
        assertEquals(CircuitBreaker.State.CLOSED, b.state());
        assertTrue(b.allow());
    }

    @Test void aFailedProbeReopensTheBreaker() {
        CircuitBreaker b = new CircuitBreaker(clock, 1, Duration.ofSeconds(30));
        b.failure();
        clock.advance(Duration.ofSeconds(31));
        assertTrue(b.allow());
        b.failure();
        assertEquals(CircuitBreaker.State.OPEN, b.state());
        assertFalse(b.allow());
    }

    @Test void aSuccessResetsTheFailureCount() {
        CircuitBreaker b = new CircuitBreaker(clock, 3, Duration.ofSeconds(30));
        b.failure(); b.failure(); b.success(); b.failure(); b.failure();
        assertEquals(CircuitBreaker.State.CLOSED, b.state(), "failures must be consecutive to count");
    }

    // ---- validator ----------------------------------------------------------------------

    static final List<RouteStop> ROUTE = StatusMathTest.ROUTE;
    final StatusValidator v = new StatusValidator();

    ProviderStatus got(String station, int delay, Instant observed) { return new ProviderStatus("12345", DATE, station, delay, observed, null); }

    Optional<String> check(CachedStatus prev, ProviderStatus c) { return v.check(ROUTE, prev, c, NOW); }

    @Test void acceptsASensibleAnswer() { assertTrue(check(null, got("NGC", 12, NOW.minusSeconds(600))).isEmpty()); }

    @Test void rejectsAStationNotOnTheRoute() { assertTrue(check(null, got("XXX", 5, NOW.minusSeconds(60))).orElseThrow().contains("not on this train's route")); }

    @Test void rejectsAbsurdDelays() {
        assertTrue(check(null, got("NGC", 1441, NOW.minusSeconds(60))).isPresent());
        assertTrue(check(null, got("NGC", -61, NOW.minusSeconds(60))).isPresent());
        assertTrue(check(null, got("NGC", 1440, NOW.minusSeconds(60))).isEmpty());
        assertTrue(check(null, got("NGC", -60, NOW.minusSeconds(60))).isEmpty());
    }

    @Test void rejectsFutureAndAncientObservations() {
        assertTrue(check(null, got("NGC", 5, NOW.plusSeconds(61))).orElseThrow().contains("future"));
        assertTrue(check(null, got("NGC", 5, NOW.plusSeconds(30))).isEmpty(), "a little clock skew is fine");
        assertTrue(check(null, got("NGC", 5, NOW.minus(Duration.ofHours(6)).minusSeconds(1))).orElseThrow().contains("6 hours"));
        assertTrue(check(null, new ProviderStatus("12345", DATE, "NGC", 5, null, null)).isPresent());
    }

    @Test void rejectsATrainThatMovedBackwards() {
        CachedStatus prev = new CachedStatus("LMG", 10, NOW.minusSeconds(900).toEpochMilli(), NOW.minusSeconds(600).toEpochMilli(), "mock");
        assertTrue(check(prev, got("NGC", 10, NOW.minusSeconds(60))).orElseThrow().contains("backwards"));
        assertTrue(check(prev, got("LMG", 10, NOW.minusSeconds(60))).isEmpty());
        assertTrue(check(prev, got("NJP", 10, NOW.minusSeconds(60))).isEmpty());
    }

    @Test void rejectsAHugeDelayJumpOnlyWhenTheLastDataWasRecent() {
        CachedStatus recent = new CachedStatus("NGC", 10, NOW.minusSeconds(900).toEpochMilli(), NOW.minusSeconds(600).toEpochMilli(), "mock");
        assertTrue(check(recent, got("NGC", 140, NOW.minusSeconds(60))).orElseThrow().contains("jumped"));
        assertTrue(check(recent, got("NGC", 130, NOW.minusSeconds(60))).isEmpty(), "exactly 120 is allowed");
        CachedStatus old = new CachedStatus("NGC", 10, NOW.minusSeconds(7200).toEpochMilli(), NOW.minusSeconds(3600).toEpochMilli(), "mock");
        assertTrue(check(old, got("NGC", 200, NOW.minusSeconds(60))).isEmpty(), "an hour is long enough for a real change");
    }

    @Test void ignoresPreviousDataFromAnotherDay() {
        CachedStatus yesterday = new CachedStatus("NDLS", 5, NOW.minus(Duration.ofDays(1)).toEpochMilli(), NOW.minus(Duration.ofDays(1)).toEpochMilli(), "mock");
        assertTrue(check(yesterday, got("GHY", 5, NOW.minusSeconds(60))).isEmpty(), "last night's arrival says nothing about today's departure");
    }

    @Test void fallbackProviderUsesTheSecondaryOnlyWhenNeeded() throws Exception {
        int[] calls = new int[2];
        ProviderAdapter failing = new ProviderAdapter() {
            public String name() { return "a"; }
            public ProviderStatus fetch(String t, LocalDate d) throws ProviderException { calls[0]++; throw new ProviderException("down"); }
        };
        ProviderAdapter working = new ProviderAdapter() {
            public String name() { return "b"; }
            public ProviderStatus fetch(String t, LocalDate d) { calls[1]++; return new ProviderStatus(t, d, "NGC", 3, NOW, null); }
        };
        assertEquals("NGC", new FallbackProviderAdapter(failing, working).fetch("12345", DATE).lastStationCode());
        assertArrayEquals(new int[] {1, 1}, calls);
        new FallbackProviderAdapter(working, failing).fetch("12345", DATE);
        assertArrayEquals(new int[] {1, 2}, calls, "the primary's success means the secondary is not called");
        assertThrows(ProviderException.class, () -> new FallbackProviderAdapter(failing, failing).fetch("12345", DATE));
        assertEquals("a+b", new FallbackProviderAdapter(failing, working).name());
    }

}
