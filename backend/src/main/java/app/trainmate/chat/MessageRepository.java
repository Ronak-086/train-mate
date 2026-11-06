package app.trainmate.chat;

import app.trainmate.db.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MessageRepository {
    public record MessageRecord(
        long id,
        UUID conversationId,
        UUID senderId,
        String body,
        Instant createdAt
    ) {}

    private final Db db;

    public MessageRepository(Db db) { this.db = db; }

    private static MessageRecord map(ResultSet rs) throws SQLException {
        return new MessageRecord(
            rs.getLong("id"),
            rs.getObject("conversation_id", UUID.class),
            rs.getObject("sender_id", UUID.class),
            rs.getString("body"),
            Db.instant(rs, "created_at")
        );
    }

    public long insert(UUID conversationId, UUID senderId, String body, Instant createdAt) {
        Long id = db.one("""
            INSERT INTO messages (conversation_id, sender_id, body, created_at)
            VALUES (?, ?, ?, ?) RETURNING id
            """, rs -> rs.getLong(1), conversationId, senderId, body, createdAt);
        return id == null ? 0 : id;
    }

    public List<MessageRecord> listAfter(UUID conversationId, long afterId, int limit) {
        return db.list("""
            SELECT id, conversation_id, sender_id, body, created_at
            FROM messages
            WHERE conversation_id = ? AND id > ?
            ORDER BY id ASC
            LIMIT ?
            """, MessageRepository::map, conversationId, afterId, limit);
    }

    public MessageRecord lastMessage(UUID conversationId) {
        return db.one("""
            SELECT id, conversation_id, sender_id, body, created_at
            FROM messages
            WHERE conversation_id = ?
            ORDER BY id DESC
            LIMIT 1
            """, MessageRepository::map, conversationId);
    }
}
