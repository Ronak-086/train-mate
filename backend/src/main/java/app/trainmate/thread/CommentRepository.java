package app.trainmate.thread;

import app.trainmate.db.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CommentRepository {
    public record CommentRecord(
        long id,
        UUID requestId,
        UUID userId,
        String authorDisplayName,
        String body,
        boolean isHidden,
        Instant createdAt
    ) {}

    private static final String SELECT_BASE = """
        SELECT c.id, c.request_id, c.user_id, u.display_name AS author_name, c.body, c.is_hidden, c.created_at
        FROM request_comments c
        JOIN users u ON u.id = c.user_id
        """;

    private final Db db;

    public CommentRepository(Db db) { this.db = db; }

    private static CommentRecord map(ResultSet rs) throws SQLException {
        return new CommentRecord(
            rs.getLong("id"),
            rs.getObject("request_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("author_name"),
            rs.getString("body"),
            rs.getBoolean("is_hidden"),
            Db.instant(rs, "created_at")
        );
    }

    public List<CommentRecord> listForPost(UUID requestId) {
        return db.list(SELECT_BASE + " WHERE c.request_id = ? AND c.is_hidden = FALSE ORDER BY c.id ASC",
            CommentRepository::map, requestId);
    }

    public CommentRecord byId(long id) {
        return db.one(SELECT_BASE + " WHERE c.id = ?", CommentRepository::map, id);
    }

    public long insert(UUID requestId, UUID userId, String body, Instant createdAt) {
        Long id = db.one("""
            INSERT INTO request_comments (request_id, user_id, body, created_at)
            VALUES (?, ?, ?, ?) RETURNING id
            """, rs -> rs.getLong(1), requestId, userId, body, createdAt);
        return id == null ? 0 : id;
    }

    public int countUserCommentsInLastHour(UUID userId, Instant since) {
        Integer c = db.one("SELECT count(*) FROM request_comments WHERE user_id = ? AND created_at >= ?",
            rs -> rs.getInt(1), userId, since);
        return c == null ? 0 : c;
    }

    public boolean hideComment(long id) {
        return db.update("UPDATE request_comments SET is_hidden = TRUE WHERE id = ?", id) > 0;
    }
}
