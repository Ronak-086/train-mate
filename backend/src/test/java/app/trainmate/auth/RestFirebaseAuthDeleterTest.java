package app.trainmate.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import org.junit.jupiter.api.Test;

class RestFirebaseAuthDeleterTest {

    @Test
    void devUsersAndBlankUidsAreSkippedSafely() {
        RestFirebaseAuthDeleter deleter = new RestFirebaseAuthDeleter("dummy_token");
        assertDoesNotThrow(() -> deleter.deleteUser(null));
        assertDoesNotThrow(() -> deleter.deleteUser(""));
        assertDoesNotThrow(() -> deleter.deleteUser("dev:u_me"));
    }

    @Test
    void missingTokenGracefullySkipsWithoutThrowing() {
        RestFirebaseAuthDeleter deleter = new RestFirebaseAuthDeleter("");
        assertDoesNotThrow(() -> deleter.deleteUser("firebase_user_123"));
    }
}
