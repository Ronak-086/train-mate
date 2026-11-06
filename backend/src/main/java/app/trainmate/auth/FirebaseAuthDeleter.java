package app.trainmate.auth;

/**
 * Handles deleting the user's authentication identity from Firebase Auth when they delete their account.
 */
public interface FirebaseAuthDeleter {
    void deleteUser(String firebaseUid);
}
