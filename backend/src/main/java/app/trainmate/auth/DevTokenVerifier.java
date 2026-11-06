package app.trainmate.auth;

import app.trainmate.http.ApiException;
import java.util.regex.Pattern;

/** Development only: accepts "dev:<name>" tokens so the app and tests can sign in as seeded users. Never enable in production. */
public final class DevTokenVerifier implements TokenVerifier {
    private static final Pattern DEV = Pattern.compile("^dev:([A-Za-z0-9_-]{1,40})$");

    @Override
    public AuthUser verify(String token) {
        var m = DEV.matcher(token == null ? "" : token);
        if (!m.matches()) throw ApiException.unauthenticated("Sign in to continue.");
        return new AuthUser("dev:" + m.group(1), null, null);
    }
}
