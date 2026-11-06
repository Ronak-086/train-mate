package app.trainmate.safety;

import app.trainmate.db.Db;
import java.time.Instant;
import java.util.UUID;

public final class ModerationRepository {
    private final Db db;

    public ModerationRepository(Db db) { this.db = db; }

    public void insertReport(UUID reporterId, UUID targetUserId, UUID requestId, Long commentId, String reason, Instant createdAt) {
        db.update("""
            INSERT INTO reports (reporter_id, target_user_id, request_id, comment_id, reason, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (reporter_id, request_id, comment_id) DO NOTHING
            """, reporterId, targetUserId, requestId, commentId, reason, createdAt);
    }

    public int distinctReportersForRequest(UUID requestId) {
        Integer c = db.one("""
            SELECT count(DISTINCT reporter_id) FROM reports
            WHERE request_id = ? AND comment_id IS NULL
            """, rs -> rs.getInt(1), requestId);
        return c == null ? 0 : c;
    }

    public int distinctReportersForComment(long commentId) {
        Integer c = db.one("""
            SELECT count(DISTINCT reporter_id) FROM reports
            WHERE comment_id = ?
            """, rs -> rs.getInt(1), commentId);
        return c == null ? 0 : c;
    }

    public void block(UUID blockerId, UUID blockedId, Instant createdAt) {
        db.update("""
            INSERT INTO blocks (blocker_id, blocked_id, created_at)
            VALUES (?, ?, ?)
            ON CONFLICT (blocker_id, blocked_id) DO NOTHING
            """, blockerId, blockedId, createdAt);
    }

    public void unblock(UUID blockerId, UUID blockedId) {
        db.update("DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?", blockerId, blockedId);
    }

    public boolean isBlockedBetween(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return false;
        Integer hit = db.one("""
            SELECT 1 FROM blocks
            WHERE (blocker_id = ? AND blocked_id = ?) OR (blocker_id = ? AND blocked_id = ?)
            LIMIT 1
            """, rs -> 1, a, b, b, a);
        return hit != null;
    }

    public boolean hasReviewed(UUID convId, UUID reviewerId) {
        return db.one("SELECT 1 FROM reviews WHERE conversation_id = ? AND reviewer_id = ?", rs -> 1, convId, reviewerId) != null;
    }

    public void insertReview(UUID convId, UUID reviewerId, UUID revieweeId, int rating, Instant createdAt) {
        db.update("""
            INSERT INTO reviews (conversation_id, reviewer_id, reviewee_id, rating, created_at)
            VALUES (?, ?, ?, ?, ?)
            """, convId, reviewerId, revieweeId, rating, createdAt);
    }

    public void updateTrustScore(UUID userId, int delta) {
        db.update("UPDATE users SET trust_score = trust_score + ? WHERE id = ?", delta, userId);
    }
}
