package app.trainmate.user;

import app.trainmate.db.Db;
import java.time.Instant;
import java.util.UUID;

public final class UserRepository {
    public record UserRow(UUID id, String firebaseUid, String displayName, String email, int trustScore, boolean blocked, Instant createdAt) {}

    private static final String COLS = "id, firebase_uid, display_name, email, trust_score, is_blocked, created_at";
    private final Db db;

    public UserRepository(Db db) { this.db = db; }

    private static UserRow map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRow(rs.getObject("id", UUID.class), rs.getString("firebase_uid"), rs.getString("display_name"), rs.getString("email"),
            rs.getInt("trust_score"), rs.getBoolean("is_blocked"), Db.instant(rs, "created_at"));
    }

    /**
     * Finds the person for a sign-in identity, creating them on first sight. One atomic statement, so two first requests
     * arriving together can't create two accounts. An existing name is never overwritten by a later sign-in.
     */
    public UserRow findOrCreate(String firebaseUid, String email, String displayName) {
        return db.one("INSERT INTO users (firebase_uid, display_name, email) VALUES (?, ?, ?) "
            + "ON CONFLICT (firebase_uid) DO UPDATE SET email = COALESCE(EXCLUDED.email, users.email) RETURNING " + COLS,
            UserRepository::map, firebaseUid, displayName, email);
    }

    public UserRow byId(UUID id) { return db.one("SELECT " + COLS + " FROM users WHERE id = ?", UserRepository::map, id); }

    public void rename(UUID id, String displayName) { db.update("UPDATE users SET display_name = ? WHERE id = ?", displayName, id); }

    public void delete(UUID id) { db.update("DELETE FROM users WHERE id = ?", id); }
}
