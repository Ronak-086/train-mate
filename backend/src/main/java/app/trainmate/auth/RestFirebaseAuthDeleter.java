package app.trainmate.auth;

import app.trainmate.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Deletes a Firebase user using Google's Identity Toolkit REST API when service credentials are provided.
 * When unconfigured or in dev mode, it logs and skips gracefully without blocking database cleanup.
 */
public final class RestFirebaseAuthDeleter implements FirebaseAuthDeleter {
    private static final System.Logger LOG = System.getLogger(RestFirebaseAuthDeleter.class.getName());
    private static final String DELETE_URL = "https://identitytoolkit.googleapis.com/v1/accounts:delete";

    private final String serviceAccountToken;
    private HttpClient client;

    public RestFirebaseAuthDeleter(String serviceAccountToken) {
        this.serviceAccountToken = serviceAccountToken != null ? serviceAccountToken.trim() : "";
    }

    private synchronized HttpClient client() {
        if (client == null) {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        }
        return client;
    }

    @Override
    public void deleteUser(String firebaseUid) {
        if (firebaseUid == null || firebaseUid.isBlank() || firebaseUid.startsWith("dev:")) {
            return;
        }
        if (serviceAccountToken.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "Firebase service token not configured; local user data deleted, Firebase identity retained for {0}", firebaseUid);
            return;
        }

        try {
            ObjectNode body = (ObjectNode) Json.tree("{}");
            body.put("localId", firebaseUid);

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(DELETE_URL))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + serviceAccountToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.write(body)))
                .build();

            HttpResponse<String> resp = client().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                LOG.log(System.Logger.Level.WARNING, "Failed to delete Firebase Auth user {0}, status {1}: {2}",
                    firebaseUid, resp.statusCode(), resp.body());
            } else {
                LOG.log(System.Logger.Level.INFO, "Successfully deleted Firebase Auth user {0}", firebaseUid);
            }
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "Error calling Firebase Auth deletion API for {0}: {1}", firebaseUid, e.getMessage());
        }
    }
}
