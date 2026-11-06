package app.trainmate.train;

import app.trainmate.store.KeyValueStore;
import app.trainmate.train.Dtos.TrainStatusDto;
import app.trainmate.train.SnapshotRepository.Snapshot;
import app.trainmate.trip.TripRepository;
import app.trainmate.util.Json;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Train status without ever blocking on a provider (spec 8.5). A request is answered from the cache (Redis), else the
 * last snapshot (Postgres), else the timetable, and if the data is missing or older than its TTL a refresh is started
 * in the background. The refresh is guarded so that many requests cause at most one provider call.
 */
public final class StatusService {
    private static final System.Logger LOG = System.getLogger(StatusService.class.getName());

    public enum Outcome { REFRESHED, FRESH, LOCKED, NOT_RUNNING, ARRIVED, NO_INTEREST, BUDGET, BREAKER, PROVIDER_ERROR, REJECTED }

    /** @param minRefreshSeconds never ask the provider again about the same train sooner than this, however many requests ask */
    public record Config(long trackedTtlSeconds, long untrackedTtlSeconds, long cacheRetainSeconds, long lockSeconds, long minRefreshSeconds) {
        public static Config defaults() { return new Config(300, 1500, 6 * 3600, 30, 30); }
    }

    private final Clock clock;
    private final KeyValueStore kv;
    private final TrainRepository trains;
    private final TripRepository trips;
    private final SnapshotRepository snapshots;
    private final ProviderAdapter provider;
    private final BudgetGuard budget;
    private final CircuitBreaker breaker;
    private final StatusValidator validator;
    private final Executor async;
    private final Config cfg;

    public StatusService(Clock clock, KeyValueStore kv, TrainRepository trains, TripRepository trips, SnapshotRepository snapshots,
                         ProviderAdapter provider, BudgetGuard budget, CircuitBreaker breaker, StatusValidator validator, Executor async, Config cfg) {
        this.clock = clock;
        this.kv = kv;
        this.trains = trains;
        this.trips = trips;
        this.snapshots = snapshots;
        this.provider = provider;
        this.budget = budget;
        this.breaker = breaker;
        this.validator = validator;
        this.async = async;
        this.cfg = cfg;
    }

    private static String cacheKey(String no, LocalDate d) { return "status:" + no + ":" + d; }

    /** Always answers quickly. {@code tracked} means the caller has this train saved, which shortens the freshness window. */
    public TrainStatusDto get(String trainNo, LocalDate date, boolean tracked) {
        List<RouteStop> stops = trains.route(trainNo);
        Instant now = clock.instant();
        safely(() -> kv.set("recent:" + trainNo + ":" + date, "1", 3600)); // someone is looking: worth refreshing

        CachedStatus cached = readCache(trainNo, date);
        boolean stale = false;
        boolean refresh = false;
        if (cached != null) {
            long ttl = tracked ? cfg.trackedTtlSeconds() : cfg.untrackedTtlSeconds();
            stale = Duration.between(cached.fetchedAt(), now).getSeconds() > ttl;
            refresh = stale;
        } else {
            Snapshot snap = snapshots.latest(trainNo, date);
            if (snap != null) {
                cached = snap.toCached();
                stale = true; // from the database: older than the cache window by definition
            }
            refresh = true;
        }
        if (refresh) async.execute(() -> {
            try {
                refresh(trainNo, date);
            } catch (RuntimeException e) {
                // Nobody is waiting on this thread, so an unexpected failure must be logged or it vanishes.
                LOG.log(System.Logger.Level.ERROR, "Background status refresh failed for " + trainNo + " on " + date, e);
            }
        });
        return StatusMath.build(trainNo, date, stops, cached, stale, now);
    }

    /** One refresh attempt: lock, check it's worthwhile, budget, breaker, fetch, validate, store. Returns what happened. */
    public Outcome refresh(String trainNo, LocalDate date) {
        String lockKey = "lock:status:" + trainNo + ":" + date;
        boolean locked;
        try {
            locked = kv.setIfAbsent(lockKey, "1", cfg.lockSeconds());
        } catch (KeyValueStore.StoreException e) {
            locked = true; // without Redis there is no lock; accept an occasional duplicate call over no refresh at all
        }
        if (!locked) return Outcome.LOCKED;
        try {
            // Requests that all saw an empty cache each queue a refresh. Whoever gets the lock second finds fresh data here.
            CachedStatus current = readCache(trainNo, date);
            if (current != null && Duration.between(current.fetchedAt(), clock.instant()).getSeconds() < cfg.minRefreshSeconds()) return Outcome.FRESH;

            var info = trains.find(trainNo);
            if (info == null || !info.runsOn(date)) return Outcome.NOT_RUNNING;
            List<RouteStop> stops = trains.route(trainNo);
            Snapshot prevSnap = snapshots.latest(trainNo, date);
            if (prevSnap != null && prevSnap.station().equals(stops.get(stops.size() - 1).code())) return Outcome.ARRIVED;

            boolean interested = trips.anyoneOn(trainNo, date) || safelyGet("recent:" + trainNo + ":" + date).isPresent();
            if (!interested) return Outcome.NO_INTEREST;
            if (!budget.tryAcquire(provider.name())) {
                LOG.log(System.Logger.Level.WARNING, "Provider budget exhausted; serving cached status for {0}", trainNo);
                return Outcome.BUDGET;
            }
            if (!breaker.allow()) return Outcome.BREAKER;

            ProviderStatus got;
            try {
                got = provider.fetch(trainNo, date);
            } catch (ProviderException e) {
                breaker.failure();
                LOG.log(System.Logger.Level.WARNING, "Provider {0} failed for {1} on {2}: {3}", provider.name(), trainNo, date, e.getMessage());
                return Outcome.PROVIDER_ERROR;
            }
            breaker.success();

            Instant now = clock.instant();
            CachedStatus prev = readCache(trainNo, date);
            if (prev == null && prevSnap != null) prev = prevSnap.toCached();
            Optional<String> problem = validator.check(stops, prev, got, now);
            if (problem.isPresent()) {
                LOG.log(System.Logger.Level.WARNING, "Rejected {0} data for {1} on {2}: {3}", provider.name(), trainNo, date, problem.get());
                return Outcome.REJECTED;
            }
            store(trainNo, date, new CachedStatus(got.lastStationCode(), got.delayMinutes(), got.observedAt().toEpochMilli(), now.toEpochMilli(), provider.name()), got.raw());
            return Outcome.REFRESHED;
        } finally {
            safely(() -> kv.delete(lockKey));
        }
    }

    /** Development only: loads the provider's current answer as if it had been fetched {@code fetchedAgo} ago. */
    public void prime(String trainNo, LocalDate date, Duration fetchedAgo) throws ProviderException {
        ProviderStatus got = provider.fetch(trainNo, date);
        Instant fetched = clock.instant().minus(fetchedAgo);
        store(trainNo, date, new CachedStatus(got.lastStationCode(), got.delayMinutes(), got.observedAt().toEpochMilli(), fetched.toEpochMilli(), provider.name()), got.raw());
    }

    private void store(String trainNo, LocalDate date, CachedStatus c, java.util.Map<String, Object> raw) {
        snapshots.insert(trainNo, date, c, raw); // Postgres first: it is the source of truth
        safely(() -> kv.set(cacheKey(trainNo, date), Json.writeString(c), cfg.cacheRetainSeconds()));
    }

    private CachedStatus readCache(String trainNo, LocalDate date) {
        try {
            Optional<String> s = kv.get(cacheKey(trainNo, date));
            return s.isPresent() ? Json.read(s.get(), CachedStatus.class) : null;
        } catch (KeyValueStore.StoreException | java.io.IOException e) {
            return null; // Redis down or a corrupt entry: fall back to the snapshot table
        }
    }

    private Optional<String> safelyGet(String key) {
        try {
            return kv.get(key);
        } catch (KeyValueStore.StoreException e) {
            return Optional.of("1"); // can't tell, so assume interest
        }
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (KeyValueStore.StoreException e) {
            LOG.log(System.Logger.Level.WARNING, "Redis unavailable: {0}", e.getMessage());
        }
    }
}
