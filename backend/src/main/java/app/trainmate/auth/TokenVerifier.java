package app.trainmate.auth;

import app.trainmate.http.ApiException;

/** Turns a Bearer token into a caller, or throws a 401. Swappable, so Firebase can be replaced or wrapped. */
public interface TokenVerifier {
    AuthUser verify(String bearerToken) throws ApiException;
}
