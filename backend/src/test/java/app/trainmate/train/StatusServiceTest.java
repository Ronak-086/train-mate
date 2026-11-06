package app.trainmate.train;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import app.trainmate.TestDb;
import app.trainmate.db.Db;
import app.trainmate.dev.MutableClock;
import app.trainmate.store.InMemoryKeyValueStore;
import app.trainmate.store.KeyValueStore;
import app.trainmate.train.Dtos.TrainStatusDto;
import app.trainmate.train.StatusService.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatusServiceTest {
    static final String NO = "12345";
    static final LocalDate DATE = LocalDate.parse("2026-10-25");
    static final Instant T0 = Instant.parse("2026-10-25T03:36:00Z"); // 09:06 IST

    /** Counts calls, and can be told to fail, stall, or lie. */
    static final class TestProvider implements ProviderAdapter {
        final ProviderAdapter real;
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean failing = false;
        volatile long delayMs = 0;
        volatile String forcedStation = null;

        TestProvider(ProviderAdapter real) { this.real = real; }

        @Override public String name() { return "mock"; }

        @Override public ProviderStatus fetch(String trainNo, LocalDate d) throws ProviderException {
            calls.incrementAndGet();
            if (delayMs > 0) try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            if (failing) throw new ProviderException("provider is down");
            ProviderStatus s = real.fetch(trainNo, d);
            return forcedStation == null ? s : new ProviderStatus(s.trainNo(), s.journeyDate(), forcedStation, s.delayMinutes(), s.observedAt(), s.raw());
        }
    }

    /** A Redis that is down. */
    static final class DeadStore implements KeyValueStore {
        private static KeyValueStore.StoreException down() { return new KeyValueStore.StoreException("Redis is down", null); }
        public Optional<String> get(String k) { throw down(); }
        public void set(String k, String v, long t) { throw down(); }
        public boolean setIfAbsent(String k, String v, long t) { throw down(); }
        public long incr(String k, long t) { throw down(); }
        public void delete(String k) { throw down(); }
        public void deleteByPrefix(String p) { throw down(); }
    }

    Db db;
    MutableClock clock;
    InMemoryKeyValueStore kv;
    TestProvider provider;
    SnapshotRepository snaps;
    TrainRepository trains;
    CircuitBreaker breaker;
    ExecutorService pool;

    @BeforeEach void setUp() throws Exception {
        assumeTrue(TestDb.available(), "No PostgreSQL at " + TestDb.URL + ": skipping");
        db = TestDb.migratedDb();
        clock = new MutableClock(T0);
        clock.freezeAt(T0);
        kv = new InMemoryKeyValueStore(clock);
        trains = new TrainRepository(db);
        snaps = new SnapshotRepository(db);
        provider = new TestProvider(new MockProviderAdapter(clock, trains, Map.of(NO + ":2026-10-25", 12)));
        breaker = new CircuitBreaker(clock, 3, Duration.ofSeconds(60));
    }

    @AfterEach void tearDown() { if (pool != null) pool.shutdownNow(); }

    StatusService service(KeyValueStore store, int budget, Executor executor) {
        return new StatusService(clock, store, trains, new app.trainmate.trip.TripRepository(db), snaps, provider,
            new BudgetGuard(store, snaps, clock, budget), breaker, new StatusValidator(), executor, StatusService.Config.defaults());
    }

    StatusService service() { return service(kv, 100, Runnable::run); }

    void interested() { kv.set("recent:" + NO + ":" + DATE, "1", 3600); }

    int snapshotRows() { return TestDb.count(db, "SELECT count(*) FROM train_status_snapshot"); }

    // ---- the design's numbers, end to end ------------------------------------------------

    @Test void primedDataGivesTheHomeDesignNumbersWithoutCallingTheProviderAgain() throws Exception {
        StatusService s = service();
        s.prime(NO, DATE, Duration.ofMinutes(4));
        assertEquals(1, provider.calls.get());
        TrainStatusDto d = s.get(NO, DATE, true);
        assertEquals("PROVIDER", d.source());
        assertEquals(12, d.delayMinutes());
        assertEquals("10–15 min late", d.delayLabel());
        assertEquals("Nagaon", d.lastStation().name());
        assertEquals("2026-10-25T08:42:00+05:30", d.lastSeenAt());
        assertEquals("2026-10-25T09:02:00+05:30", d.fetchedAt());
        assertEquals("2026-10-25T09:20:00+05:30", d.nextStation().etaFrom());
        assertEquals("2026-10-25T09:35:00+05:30", d.nextStation().etaTo());
        assertFalse(d.stale());
        assertEquals(1, provider.calls.get(), "fresh data must not trigger a provider call");
    }

    @Test void anEmptyCacheServesTheTimetableAtOnceAndRefreshesInTheBackground() {
        StatusService s = service();
        TrainStatusDto first = s.get(NO, DATE, true);
        assertEquals("SCHEDULE", first.source(), "never block on the provider");
        assertEquals(1, provider.calls.get(), "but a refresh was started");
        TrainStatusDto second = s.get(NO, DATE, true);
        assertEquals("PROVIDER", second.source());
        assertEquals(12, second.delayMinutes());
        assertEquals("2026-10-25T09:06:00+05:30", second.fetchedAt());
        assertEquals(1, provider.calls.get());
    }

    // ---- staleness -------------------------------------------------------------------------

    @Test void trackedTrainsGoStaleAfterFiveMinutesAndUntrackedAfterTwentyFive() throws Exception {
        StatusService s = service();
        s.prime(NO, DATE, Duration.ofMinutes(4));
        clock.freezeAt(T0.plus(Duration.ofMinutes(2))); // data is now 6 minutes old
        assertFalse(s.get(NO, DATE, false).stale(), "an untracked train tolerates 25 minutes");
        assertEquals(1, provider.calls.get());
        TrainStatusDto d = s.get(NO, DATE, true);
        assertTrue(d.stale(), "this reply is flagged stale…");
        assertEquals("Updating. This may be a few minutes out of date.", d.notice());
        assertEquals(2, provider.calls.get(), "…and exactly one refresh was started");
        TrainStatusDto after = s.get(NO, DATE, true);
        assertFalse(after.stale());
        assertEquals("2026-10-25T09:08:00+05:30", after.fetchedAt());
        assertEquals(2, provider.calls.get());
    }

    // ---- stampede protection -----------------------------------------------------------------

    @Test void twelveSimultaneousRequestsMakeExactlyOneProviderCall() throws Exception {
        provider.delayMs = 300;
        pool = Executors.newFixedThreadPool(16);
        StatusService s = service(kv, 100, pool);
        CountDownLatch go = new CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<TrainStatusDto>>();
        for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> { go.await(); return s.get(NO, DATE, true); }));
        go.countDown();
        for (var f : futures) assertEquals("SCHEDULE", f.get(5, TimeUnit.SECONDS).source(), "every caller got an immediate answer");
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, provider.calls.get(), "the lock and the freshness re-check must collapse 12 refreshes into 1");
        assertEquals(1, snapshotRows());
    }

    @Test void refreshesThatQueueUpBehindEachOtherDoNotRepeatTheCall() throws Exception {
        // Here the lock alone can't help: a single background worker runs the refreshes one after another, so each
        // finds the lock free. Only the freshness re-check (data fetched a moment ago) stops 12 provider calls.
        provider.delayMs = 40;
        ExecutorService background = Executors.newSingleThreadExecutor();
        pool = Executors.newFixedThreadPool(12);
        StatusService s = service(kv, 100, background);
        CountDownLatch go = new CountDownLatch(1);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<TrainStatusDto>>();
        for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> { go.await(); return s.get(NO, DATE, true); }));
        go.countDown();
        for (var f : futures) f.get(5, TimeUnit.SECONDS);
        background.shutdown();
        assertTrue(background.awaitTermination(10, TimeUnit.SECONDS), "all queued refreshes finished");
        assertEquals(1, provider.calls.get());
        assertEquals(1, snapshotRows());
    }

    @Test void anUnexpectedFailureInTheBackgroundNeverReachesTheCallerAndFreesTheLock() {
        ProviderAdapter exploding = new ProviderAdapter() {
            public String name() { return "mock"; }
            public ProviderStatus fetch(String t, LocalDate d) { throw new IllegalStateException("boom: not a ProviderException"); }
        };
        StatusService s = new StatusService(clock, kv, trains, new app.trainmate.trip.TripRepository(db), snaps, exploding,
            new BudgetGuard(kv, snaps, clock, 100), breaker, new StatusValidator(), Runnable::run, StatusService.Config.defaults());
        TrainStatusDto d = assertDoesNotThrow(() -> s.get(NO, DATE, true));
        assertEquals("SCHEDULE", d.source(), "the caller still gets an answer");
        assertTrue(kv.get("lock:status:" + NO + ":" + DATE).isEmpty(), "and the lock is not left behind");
    }

    @Test void aHeldLockMeansTheRefreshStandsDown() {
        interested();
        kv.setIfAbsent("lock:status:" + NO + ":" + DATE, "1", 30);
        assertEquals(Outcome.LOCKED, service().refresh(NO, DATE));
        assertEquals(0, provider.calls.get());
    }

    @Test void theLockIsReleasedEvenWhenTheProviderFails() {
        interested();
        provider.failing = true;
        assertEquals(Outcome.PROVIDER_ERROR, service().refresh(NO, DATE));
        assertTrue(kv.get("lock:status:" + NO + ":" + DATE).isEmpty());
    }

    // ---- budget, breaker, validation, failure ---------------------------------------------------

    @Test void whenTheDailyBudgetIsSpentTheProviderIsLeftAloneAndOldDataIsStillServed() throws Exception {
        StatusService s = service(kv, 1, Runnable::run);
        s.prime(NO, DATE, Duration.ofMinutes(4)); // prime does not use the budget
        interested();
        assertEquals(Outcome.REFRESHED, s.refresh(NO, DATE)); // uses the one call in the budget
        clock.freezeAt(T0.plus(Duration.ofMinutes(10)));
        assertEquals(Outcome.BUDGET, s.refresh(NO, DATE));
        int calls = provider.calls.get();
        TrainStatusDto d = s.get(NO, DATE, true);
        assertEquals("PROVIDER", d.source(), "stale data beats no data");
        assertTrue(d.stale());
        assertEquals(calls, provider.calls.get(), "no further provider calls once the budget is spent");
        assertEquals(1, snaps.usage("mock", DATE));
    }

    @Test void theBudgetFallsBackToThePostgresCountWhenRedisIsDown() {
        StatusService s = service(new DeadStore(), 2, Runnable::run);
        assertEquals(Outcome.REFRESHED, s.refresh(NO, DATE));
        clock.freezeAt(T0.plus(Duration.ofMinutes(5)));
        assertEquals(Outcome.REFRESHED, s.refresh(NO, DATE));
        clock.freezeAt(T0.plus(Duration.ofMinutes(10)));
        assertEquals(Outcome.BUDGET, s.refresh(NO, DATE), "the third call is over the budget of 2");
        assertEquals(2, provider.calls.get());
        assertEquals(2, snaps.usage("mock", DATE));
    }

    @Test void theBreakerStopsCallsToAFailingProviderThenProbesAgain() {
        interested();
        StatusService s = service();
        provider.failing = true;
        for (int i = 0; i < 3; i++) assertEquals(Outcome.PROVIDER_ERROR, s.refresh(NO, DATE));
        assertEquals(Outcome.BREAKER, s.refresh(NO, DATE));
        assertEquals(3, provider.calls.get(), "the open breaker protects the provider");
        provider.failing = false;
        clock.freezeAt(T0.plus(Duration.ofSeconds(61)));
        assertEquals(Outcome.REFRESHED, s.refresh(NO, DATE), "one probe succeeds and closes the breaker");
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test void implausibleAnswersAreRejectedAndTheOldDataStays() throws Exception {
        StatusService s = service();
        s.prime(NO, DATE, Duration.ofMinutes(10));
        interested();
        provider.forcedStation = "XXX";
        assertEquals(Outcome.REJECTED, s.refresh(NO, DATE));
        assertEquals(1, snapshotRows(), "nothing was stored");
        TrainStatusDto d = s.get(NO, DATE, false);
        assertEquals("Nagaon", d.lastStation().name());
    }

    @Test void aProviderOutageStillGetsAnAnswer() throws Exception {
        StatusService s = service();
        s.prime(NO, DATE, Duration.ofMinutes(4));
        clock.freezeAt(T0.plus(Duration.ofMinutes(20)));
        provider.failing = true;
        TrainStatusDto d = assertDoesNotThrow(() -> s.get(NO, DATE, true));
        assertEquals("PROVIDER", d.source());
        assertTrue(d.stale());
        assertEquals("Nagaon", d.lastStation().name());
    }

    // ---- when not to bother ------------------------------------------------------------------------

    @Test void trainsThatDoNotRunThatDayAreNotRefreshed() {
        db.update("UPDATE trains SET run_days = 'M------' WHERE train_no = ?", NO); // 25 Oct 2026 is a Sunday
        interested();
        assertEquals(Outcome.NOT_RUNNING, service().refresh(NO, DATE));
        assertEquals(0, provider.calls.get());
    }

    @Test void nobodyLookingMeansNoCall() {
        assertEquals(Outcome.NO_INTEREST, service().refresh(NO, DATE));
        assertEquals(0, provider.calls.get());
        interested();
        assertEquals(Outcome.REFRESHED, service().refresh(NO, DATE));
    }

    @Test void aSavedTripCountsAsInterestEvenWithNoRecentRequests() {
        var users = new app.trainmate.user.UserRepository(db);
        var trips = new app.trainmate.trip.TripRepository(db);
        var u = users.findOrCreate("dev:u_me", null, "Asha K.");
        trips.insert(u.id(), NO, DATE, "GHY", "NDLS", "3A", "B3", 34, "UPPER", null);
        assertEquals(Outcome.REFRESHED, service().refresh(NO, DATE));
    }

    @Test void arrivedTrainsAreLeftAlone() {
        clock.freezeAt(Instant.parse("2026-10-25T09:00:00Z")); // 14:30 IST, after arrival at 14:02
        interested();
        StatusService s = service();
        assertEquals(Outcome.REFRESHED, s.refresh(NO, DATE));
        clock.freezeAt(Instant.parse("2026-10-25T09:10:00Z"));
        interested();
        assertEquals(Outcome.ARRIVED, s.refresh(NO, DATE));
        assertEquals(1, provider.calls.get());
        assertEquals("Arrived at New Delhi.", s.get(NO, DATE, true).notice());
    }

    @Test void timetableOnlyTrainsKeepTheirHonestLabel() {
        TrainStatusDto d = service().get("12346", DATE, true);
        assertEquals("SCHEDULE", d.source());
        assertTrue(d.notice().toLowerCase().contains("timetable only"));
    }

    // ---- Redis is disposable --------------------------------------------------------------------------

    @Test void aRedisFlushLosesNothingBecausePostgresRemembers() throws Exception {
        StatusService s = service();
        s.prime(NO, DATE, Duration.ofMinutes(4));
        kv.deleteByPrefix("status:");
        TrainStatusDto d = s.get(NO, DATE, true);
        assertEquals("PROVIDER", d.source());
        assertEquals("2026-10-25T09:02:00+05:30", d.fetchedAt());
        assertTrue(d.stale(), "data rebuilt from the database counts as stale and triggers a refresh");
    }

    @Test void withRedisDownTheServiceStillAnswersAndRefreshes() throws Exception {
        StatusService s = service(new DeadStore(), 100, Runnable::run);
        s.prime(NO, DATE, Duration.ofMinutes(4));
        TrainStatusDto d = assertDoesNotThrow(() -> s.get(NO, DATE, true));
        assertEquals("PROVIDER", d.source(), "served from the Postgres snapshot");
        assertTrue(provider.calls.get() >= 2, "and it tried to refresh");
    }

    @Test void aCorruptCacheEntryIsIgnored() {
        kv.set("status:" + NO + ":" + DATE, "{not json", 600);
        TrainStatusDto d = assertDoesNotThrow(() -> service().get(NO, DATE, true));
        assertEquals("SCHEDULE", d.source());
    }

    @Test void everyRefreshIsRecordedInPostgres() throws Exception {
        StatusService s = service();
        s.prime(NO, DATE, Duration.ofMinutes(4));
        var snap = snaps.latest(NO, DATE);
        assertEquals("NGC", snap.station());
        assertEquals(12, snap.delay());
        assertEquals("mock", snap.source());
        assertEquals(Instant.parse("2026-10-25T03:32:00Z"), snap.fetchedAt());
        assertEquals(Instant.parse("2026-10-25T03:12:00Z"), snap.observedAt());
        assertNotNull(db.one("SELECT raw_json FROM train_status_snapshot LIMIT 1", rs -> rs.getString(1)));
    }
}
