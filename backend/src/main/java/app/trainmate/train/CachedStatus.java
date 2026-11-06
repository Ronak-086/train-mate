package app.trainmate.train;

import java.time.Instant;

/**
 * What we last learned about a running train. Stored as JSON in Redis and rebuilt from the snapshot table when Redis is
 * empty. Times are epoch milliseconds so the JSON needs no date module.
 */
public record CachedStatus(String lastStationCode, int delayMinutes, long observedAtMs, long fetchedAtMs, String source) {
    public Instant observedAt() { return Instant.ofEpochMilli(observedAtMs); }

    public Instant fetchedAt() { return Instant.ofEpochMilli(fetchedAtMs); }
}
