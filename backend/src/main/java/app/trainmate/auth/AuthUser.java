package app.trainmate.auth;

/** Who the sign-in provider says is calling. {@code uid} is Firebase's user id (or "dev:..." in development). */
public record AuthUser(String uid, String email, String name) {}
