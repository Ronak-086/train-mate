package app.trainmate.chat;

import app.trainmate.db.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ConversationRepository {
    public record ConversationRecord(
        UUID id,
        UUID requestId,
        String requestTitle,
        String requestKind,
        String requestCoach,
        UUID requesterId,
        String requesterName,
        UUID helperId,
        String helperName,
        String status,
        boolean requesterDone,
        boolean helperDone,
        Instant createdAt
    ) {}

    private static final String SELECT_BASE = """
        SELECT c.id, c.request_id, r.title AS request_title, r.kind AS request_kind, r.coach AS request_coach,
               c.requester_id, u_req.display_name AS requester_name,
               c.helper_id, u_help.display_name AS helper_name,
               c.status, c.requester_done, c.helper_done, c.created_at
        FROM conversations c
        JOIN requests r ON r.id = c.request_id
        JOIN users u_req ON u_req.id = c.requester_id
        JOIN users u_help ON u_help.id = c.helper_id
        """;

    private final Db db;

    public ConversationRepository(Db db) { this.db = db; }

    private static ConversationRecord map(ResultSet rs) throws SQLException {
        return new ConversationRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("request_id", UUID.class),
            rs.getString("request_title"),
            rs.getString("request_kind"),
            rs.getString("request_coach"),
            rs.getObject("requester_id", UUID.class),
            rs.getString("requester_name"),
            rs.getObject("helper_id", UUID.class),
            rs.getString("helper_name"),
            rs.getString("status"),
            rs.getBoolean("requester_done"),
            rs.getBoolean("helper_done"),
            Db.instant(rs, "created_at")
        );
    }

    public ConversationRecord byId(UUID id) {
        return db.one(SELECT_BASE + " WHERE c.id = ?", ConversationRepository::map, id);
    }

    public ConversationRecord findExisting(UUID requestId, UUID helperId) {
        return db.one(SELECT_BASE + " WHERE c.request_id = ? AND c.helper_id = ?",
            ConversationRepository::map, requestId, helperId);
    }

    public UUID insert(UUID requestId, UUID requesterId, UUID helperId, Instant createdAt) {
        return db.one("""
            INSERT INTO conversations (request_id, requester_id, helper_id, status, created_at)
            VALUES (?, ?, ?, 'PENDING', ?)
            RETURNING id
            """, rs -> rs.getObject(1, UUID.class), requestId, requesterId, helperId, createdAt);
    }

    public List<ConversationRecord> listForUser(UUID userId) {
        return db.list(SELECT_BASE + " WHERE c.requester_id = ? OR c.helper_id = ? ORDER BY c.created_at DESC",
            ConversationRepository::map, userId, userId);
    }

    public boolean updateStatus(UUID id, String status) {
        return db.update("UPDATE conversations SET status = ? WHERE id = ?", status, id) > 0;
    }

    public boolean markDone(UUID id, boolean requesterDone, boolean helperDone, String status) {
        return db.update("""
            UPDATE conversations
            SET requester_done = ?, helper_done = ?, status = ?
            WHERE id = ?
            """, requesterDone, helperDone, status, id) > 0;
    }
}
