package app.trainmate.store;

import app.trainmate.dev.MutableClock;
import java.time.Duration;
import java.time.Instant;

class InMemoryKeyValueStoreTest extends KeyValueStoreContract {
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-25T03:36:00Z"));
    private final InMemoryKeyValueStore store = new InMemoryKeyValueStore(clock);

    @Override KeyValueStore store() { return store; }

    @Override void pass(double seconds) { clock.advance(Duration.ofMillis((long) (seconds * 1000))); }
}
