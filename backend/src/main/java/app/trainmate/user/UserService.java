package app.trainmate.user;

import app.trainmate.auth.AuthUser;
import app.trainmate.auth.FirebaseAuthDeleter;
import app.trainmate.http.ApiException;
import app.trainmate.http.Body;
import app.trainmate.user.UserRepository.UserRow;
import app.trainmate.util.Ist;
import java.util.UUID;

public final class UserService {
    public record MeDto(String id, String displayName, String email, int trustScore, String createdAt) {}

    private final UserRepository users;
    private final FirebaseAuthDeleter firebaseDeleter;

    public UserService(UserRepository users) {
        this(users, null);
    }

    public UserService(UserRepository users, FirebaseAuthDeleter firebaseDeleter) {
        this.users = users;
        this.firebaseDeleter = firebaseDeleter;
    }

    /** The app's own record of whoever the sign-in provider says is calling. Blocked accounts are refused here. */
    public UserRow resolve(AuthUser who) {
        UserRow u = users.findOrCreate(who.uid(), who.email(), DisplayNames.from(who.name(), who.email()));
        if (u.blocked()) throw ApiException.forbidden("ACCOUNT_BLOCKED", "This account has been blocked.");
        return u;
    }

    public static MeDto dto(UserRow u) { return new MeDto(u.id().toString(), u.displayName(), u.email(), u.trustScore(), Ist.iso(u.createdAt())); }

    public MeDto rename(UserRow me, Body body) {
        String name = body.str("displayName", 1, 40, true);
        if (name.matches("(?is).*(https?://|www\\.).*")) throw ApiException.bad("LINKS_NOT_ALLOWED", "Names can't contain links.");
        users.rename(me.id(), name);
        return dto(users.byId(me.id()));
    }

    public void delete(UserRow me) {
        users.delete(me.id());
        if (firebaseDeleter != null && me.firebaseUid() != null) {
            firebaseDeleter.deleteUser(me.firebaseUid());
        }
    }

    public static UUID parseId(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

}
