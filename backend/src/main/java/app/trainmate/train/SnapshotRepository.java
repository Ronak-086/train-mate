package app.trainmate.train;

import app.trainmate.db.Db;
import app.trainmate.util.Json;
import java.time.Instant;
import java.time.LocalDate;

/** The durable record of what providers told us, plus the daily call counter. Redis can lose its copy; this cannot. */
public final class SnapshotRepository {
    public record Snapshot(String station, Integer delay, Instant observedAt, Instant fetchedAt, String source) {
        public CachedStatus toCached() {
            return new CachedStatus(station, delay, observedAt.toEpochMilli(), fetchedAt.toEpochMilli(), source);
        }
    }

    private final Db db;

    public SnapshotRepository(Db db) { this.db = db; }

    public Snapshot latest(String trainNo, LocalDate date) {
        return db.one("""
            SELECT current_station, delay_min, observed_at, fetched_at, source FROM train_status_snapshot
            WHERE train_no = ? AND journey_date = ? AND current_station IS NOT NULL AND delay_min IS NOT NULL AND observed_at IS NOT NULL
            ORDER BY fetched_at DESC LIMIT 1
            """, rs -> new Snapshot(rs.getString("current_station"), rs.getInt("delay_min"), Db.instant(rs, "observed_at"),
                Db.instant(rs, "fetched_at"), rs.getString("source")), trainNo, date);
    }

    public void insert(String trainNo, LocalDate date, CachedStatus s, Object raw) {
        db.update("INSERT INTO train_status_snapshot (train_no, journey_date, current_station, delay_min, observed_at, fetched_at, source, raw_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
            trainNo, date, s.lastStationCode(), s.delayMinutes(), s.observedAt(), s.fetchedAt(), s.source(), raw == null ? null : Json.writeString(raw));
    }

    /** Adds one call to today's count and returns the new total. */
    public int incrementUsage(String provider, LocalDate day) {
        return db.one("INSERT INTO api_usage (provider, day, calls) VALUES (?, ?, 1) ON CONFLICT (provider, day) DO UPDATE SET calls = api_usage.calls + 1 RETURNING calls",
            rs -> rs.getInt(1), provider, day);
    }

    public int usage(String provider, LocalDate day) {
        Integer n = db.one("SELECT calls FROM api_usage WHERE provider = ? AND day = ?", rs -> rs.getInt(1), provider, day);
        return n == null ? 0 : n;
    }
}
