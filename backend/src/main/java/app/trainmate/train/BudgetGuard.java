package app.trainmate.train;

import app.trainmate.store.KeyValueStore;
import app.trainmate.util.Ist;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Keeps provider calls inside the daily quota (spec 8.6). Redis counts the calls (fast, atomic); if Redis is down the
 * database count is used instead, so an outage can't blow the quota. Every permitted call is also recorded in api_usage.
 */
public final class BudgetGuard {
    private final KeyValueStore kv;
    private final SnapshotRepository usage;
    private final Clock clock;
    private final int dailyBudget;

    public BudgetGuard(KeyValueStore kv, SnapshotRepository usage, Clock clock, int dailyBudget) {
        this.kv = kv;
        this.usage = usage;
        this.clock = clock;
        this.dailyBudget = dailyBudget;
    }

    /** True if a call is allowed now (and it has been counted). */
    public boolean tryAcquire(String provider) {
        LocalDate day = Ist.date(clock.instant());
        try {
            long n = kv.incr("budget:" + provider + ":" + day, 2 * 86_400);
            if (n > dailyBudget) return false;
            usage.incrementUsage(provider, day);
            return true;
        } catch (KeyValueStore.StoreException redisDown) {
            if (usage.usage(provider, day) >= dailyBudget) return false;
            usage.incrementUsage(provider, day);
            return true;
        }
    }
}
