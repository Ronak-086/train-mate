package app.trainmate.post;

import app.trainmate.db.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PostRepository {
    public record RequestRecord(
        UUID id,
        UUID tripId,
        UUID userId,
        String authorDisplayName,
        String trainNo,
        LocalDate journeyDate,
        String coach,
        String kind,
        String scope,
        String category,
        String title,
        String note,
        boolean isUrgent,
        boolean isHidden,
        String offeringBerth,
        String wantsBerth,
        int groupSize,
        String status,
        Instant createdAt,
        Instant expiresAt
    ) {}

    private static final String SELECT_BASE = """
        SELECT r.id, r.trip_id, r.user_id, u.display_name AS author_name, r.train_no, r.journey_date, r.coach,
               r.kind, r.scope, r.category, r.title, r.note, r.is_urgent, r.is_hidden, r.offering_berth,
               r.wants_berth, r.group_size, r.status, r.created_at, r.expires_at
        FROM requests r
        JOIN users u ON u.id = r.user_id
        """;

    private final Db db;

    public PostRepository(Db db) { this.db = db; }

    private static RequestRecord map(ResultSet rs) throws SQLException {
        return new RequestRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("trip_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("author_name"),
            rs.getString("train_no"),
            rs.getObject("journey_date", LocalDate.class),
            rs.getString("coach"),
            rs.getString("kind"),
            rs.getString("scope"),
            rs.getString("category"),
            rs.getString("title"),
            rs.getString("note"),
            rs.getBoolean("is_urgent"),
            rs.getBoolean("is_hidden"),
            rs.getString("offering_berth"),
            rs.getString("wants_berth"),
            rs.getInt("group_size"),
            rs.getString("status"),
            Db.instant(rs, "created_at"),
            Db.instant(rs, "expires_at")
        );
    }

    public UUID insert(UUID tripId, UUID userId, String trainNo, LocalDate journeyDate, String coach,
                       String kind, String scope, String category, String title, String note,
                       boolean isUrgent, String offeringBerth, String wantsBerth, int groupSize,
                       Instant createdAt, Instant expiresAt) {
        return db.one("""
            INSERT INTO requests (trip_id, user_id, train_no, journey_date, coach, kind, scope, category,
                                  title, note, is_urgent, offering_berth, wants_berth, group_size,
                                  created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """, rs -> rs.getObject(1, UUID.class),
            tripId, userId, trainNo, journeyDate, coach, kind, scope, category,
            title, note, isUrgent, offeringBerth, wantsBerth, groupSize,
            createdAt, expiresAt
        );
    }

    public RequestRecord byId(UUID id) {
        return db.one(SELECT_BASE + " WHERE r.id = ?", PostRepository::map, id);
    }

    public boolean updateStatus(UUID id, String status) {
        return db.update("UPDATE requests SET status = ? WHERE id = ?", status, id) > 0;
    }

    public boolean patch(UUID id, UUID userId, String title, String note, String status) {
        StringBuilder sql = new StringBuilder("UPDATE requests SET ");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        if (title != null) {
            sql.append("title = ?");
            args.add(title);
            first = false;
        }
        if (note != null) {
            if (!first) sql.append(", ");
            sql.append("note = ?");
            args.add(note);
            first = false;
        }
        if (status != null) {
            if (!first) sql.append(", ");
            sql.append("status = ?");
            args.add(status);
        }
        sql.append(" WHERE id = ? AND user_id = ?");
        args.add(id);
        args.add(userId);
        return db.update(sql.toString(), args.toArray()) > 0;
    }

    public boolean delete(UUID id, UUID userId) {
        return db.update("DELETE FROM requests WHERE id = ? AND user_id = ?", id, userId) > 0;
    }

    public Instant lastPostTime(UUID userId) {
        return db.one("SELECT max(created_at) AS max_created FROM requests WHERE user_id = ?", rs -> Db.instant(rs, "max_created"), userId);
    }

    public int countUserPostsToday(UUID userId, Instant dayStart) {
        Integer c = db.one("SELECT count(*) FROM requests WHERE user_id = ? AND created_at >= ?",
            rs -> rs.getInt(1), userId, dayStart);
        return c == null ? 0 : c;
    }

    public int countBroadcastsForJourney(UUID userId, UUID tripId) {
        Integer c = db.one("""
            SELECT count(*) FROM requests
            WHERE user_id = ? AND trip_id = ? AND (kind = 'ALERT' OR scope = 'TRAIN')
            """, rs -> rs.getInt(1), userId, tripId);
        return c == null ? 0 : c;
    }

    public List<RequestRecord> listForTrain(String trainNo, LocalDate date) {
        return db.list(SELECT_BASE + " WHERE r.train_no = ? AND r.journey_date = ? ORDER BY r.created_at DESC",
            PostRepository::map, trainNo, date);
    }

    public Map<String, Integer> reactionCounts(UUID requestId) {
        Map<String, Integer> counts = new HashMap<>();
        counts.put("CONFIRMED", 0);
        counts.put("HELPFUL", 0);
        counts.put("OUTDATED", 0);
        db.list("SELECT type, count(*) AS c FROM reactions WHERE request_id = ? GROUP BY type", rs -> {
            counts.put(rs.getString("type"), rs.getInt("c"));
            return null;
        }, requestId);
        return counts;
    }

    public List<String> userReactions(UUID requestId, UUID userId) {
        return db.list("SELECT type FROM reactions WHERE request_id = ? AND user_id = ?",
            rs -> rs.getString("type"), requestId, userId);
    }

    public String userConversationId(UUID requestId, UUID userId) {
        UUID id = db.one("SELECT id FROM conversations WHERE request_id = ? AND helper_id = ?",
            rs -> rs.getObject("id", UUID.class), requestId, userId);
        return id == null ? null : id.toString();
    }

    public int replyCount(UUID requestId, String kind) {
        if ("SWAP".equals(kind) || "HELP".equals(kind)) {
            Integer count = db.one("SELECT count(*) FROM conversations WHERE request_id = ? AND status != 'DECLINED'",
                rs -> rs.getInt(1), requestId);
            return count == null ? 0 : count;
        }
        Integer count = db.one("SELECT count(*) FROM request_comments WHERE request_id = ? AND is_hidden = FALSE",
            rs -> rs.getInt(1), requestId);
        return count == null ? 0 : count;
    }

    public boolean hidePost(UUID requestId) {
        return db.update("UPDATE requests SET is_hidden = TRUE WHERE id = ?", requestId) > 0;
    }
}
