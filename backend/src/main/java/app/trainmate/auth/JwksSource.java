package app.trainmate.auth;

import java.security.PublicKey;
import java.util.Optional;

/** Where the verifier finds the public key for a token's key id. */
public interface JwksSource {
    Optional<PublicKey> key(String kid);
}
