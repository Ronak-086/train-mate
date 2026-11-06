package app.trainmate.thread;

import app.trainmate.db.Db;
import java.time.Instant;
import java.util.UUID;

public final class ReactionRepository {
    private final Db db;

    public ReactionRepository(Db db) { this.db = db; }

    public void add(UUID requestId, UUID userId, String type, Instant createdAt) {
        db.update("""
            INSERT INTO reactions (request_id, user_id, type, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (request_id, user_id, type) DO NOTHING
            """, requestId, userId, type, createdAt);
    }

    public void remove(UUID requestId, UUID userId, String type) {
        db.update("DELETE FROM reactions WHERE request_id = ? AND user_id = ? AND type = ?",
            requestId, userId, type);
    }

    public int countOutdated(UUID requestId) {
        Integer c = db.one("SELECT count(*) FROM reactions WHERE request_id = ? AND type = 'OUTDATED'",
            rs -> rs.getInt(1), requestId);
        return c == null ? 0 : c;
    }
}
