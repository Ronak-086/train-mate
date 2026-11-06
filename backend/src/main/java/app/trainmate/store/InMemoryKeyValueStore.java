package app.trainmate.store;

import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A single-process stand-in for Redis. Used when no Redis URL is configured, and in tests. */
public final class InMemoryKeyValueStore implements KeyValueStore {
    private record Entry(String value, long expiresAtMs) {}

    private final ConcurrentHashMap<String, Entry> data = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryKeyValueStore(Clock clock) { this.clock = clock; }

    private long now() { return clock.millis(); }

    private Entry live(String key) {
        Entry e = data.get(key);
        if (e != null && e.expiresAtMs() <= now()) {
            data.remove(key, e);
            return null;
        }
        return e;
    }

    @Override public Optional<String> get(String key) { Entry e = live(key); return e == null ? Optional.empty() : Optional.of(e.value()); }

    @Override public void set(String key, String value, long ttlSeconds) { data.put(key, new Entry(value, now() + ttlSeconds * 1000)); }

    @Override public boolean setIfAbsent(String key, String value, long ttlSeconds) {
        Entry fresh = new Entry(value, now() + ttlSeconds * 1000);
        while (true) {
            Entry cur = data.putIfAbsent(key, fresh);
            if (cur == null) return true;
            if (cur.expiresAtMs() > now()) return false;
            if (data.replace(key, cur, fresh)) return true;
        }
    }

    @Override public long incr(String key, long ttlSecondsIfNew) {
        long[] result = new long[1];
        data.compute(key, (k, cur) -> {
            if (cur == null || cur.expiresAtMs() <= now()) {
                result[0] = 1;
                return new Entry("1", now() + ttlSecondsIfNew * 1000);
            }
            result[0] = Long.parseLong(cur.value()) + 1;
            return new Entry(String.valueOf(result[0]), cur.expiresAtMs());
        });
        return result[0];
    }

    @Override public void delete(String key) { data.remove(key); }

    @Override public void deleteByPrefix(String prefix) { data.keySet().removeIf(k -> k.startsWith(prefix)); }
}
