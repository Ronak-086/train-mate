package app.trainmate.store;

import java.util.Optional;

/**
 * The slice of Redis the app uses: cache, locks and counters. Redis is disposable (Postgres is the source of truth),
 * so callers treat a {@link StoreException} as "cache unavailable" and carry on.
 */
public interface KeyValueStore {
    Optional<String> get(String key);

    void set(String key, String value, long ttlSeconds);

    /** SET key value NX EX ttl. True when this caller created the key (i.e. holds the lock). */
    boolean setIfAbsent(String key, String value, long ttlSeconds);

    /** Atomically adds 1 and returns the new value. The expiry is set when the key is created. */
    long incr(String key, long ttlSecondsIfNew);

    void delete(String key);

    /** Removes every key starting with the prefix. For development resets only. */
    void deleteByPrefix(String prefix);

    @SuppressWarnings("serial")
    class StoreException extends RuntimeException {
        public StoreException(String message, Throwable cause) { super(message, cause); }
    }
}
