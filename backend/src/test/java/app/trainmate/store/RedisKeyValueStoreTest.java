package app.trainmate.store;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.InetSocketAddress;
import java.net.Socket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the shared contract against a real Redis (REDIS_URL, default localhost). Skipped when none is reachable. */
class RedisKeyValueStoreTest extends KeyValueStoreContract {
    static final String URL = System.getenv().getOrDefault("REDIS_URL", "redis://localhost:6379");
    private RedisKeyValueStore store;

    @BeforeEach void connect() {
        var u = java.net.URI.create(URL);
        boolean up;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(u.getHost(), u.getPort() > 0 ? u.getPort() : 6379), 500);
            up = true;
        } catch (Exception e) {
            up = false;
        }
        assumeTrue(up, "No Redis at " + URL + ": skipping");
        store = new RedisKeyValueStore(URL, 2000);
    }

    @AfterEach void close() { if (store != null) store.close(); }

    @Override KeyValueStore store() { return store; }

    @Override void pass(double seconds) throws Exception { Thread.sleep((long) (seconds * 1000)); }

    @Test void reconnectsAfterTheServerDropsTheConnection() throws Exception {
        String k = key("reconnect");
        store.set(k, "before", 60);
        Process p = new ProcessBuilder("redis-cli", "client", "kill", "type", "normal").redirectErrorStream(true).start();
        p.waitFor();
        assertEquals("before", store.get(k).orElseThrow(), "the client should reconnect and carry on");
    }

    @Test void anUnreachableRedisIsAStoreExceptionNotACrash() {
        try (var dead = new RedisKeyValueStore("redis://localhost:1", 300)) {
            assertThrows(KeyValueStore.StoreException.class, () -> dead.get("x"));
        }
    }

    @Test void authenticatesWhenTheUrlCarriesAPassword() {
        // A wrong password against a server with no password set is an error, proving AUTH is actually sent.
        try (var bad = new RedisKeyValueStore("redis://:wrong-password@localhost:6379", 1000)) {
            assertThrows(KeyValueStore.StoreException.class, () -> bad.get("x"));
        }
    }
}
