package app.trainmate.store;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Behaviour every KeyValueStore must have. Subclasses supply the store and a way to let time pass. */
abstract class KeyValueStoreContract {
    abstract KeyValueStore store();

    /** Lets {@code seconds} of the store's time pass (a clock jump, or a real sleep). */
    abstract void pass(double seconds) throws Exception;

    String key(String name) { return "test:" + System.nanoTime() + ":" + name; }

    @Test void missingKeyIsEmpty() { assertTrue(store().get(key("nope")).isEmpty()); }

    @Test void setThenGet() {
        String k = key("a");
        store().set(k, "{\"x\":1}", 60);
        assertEquals("{\"x\":1}", store().get(k).orElseThrow());
    }

    @Test void valuesWithUnicodeAndNewlinesSurvive() {
        String k = key("u");
        store().set(k, "Nagaon ✓\nline two", 60);
        assertEquals("Nagaon ✓\nline two", store().get(k).orElseThrow());
    }

    @Test void keysExpire() throws Exception {
        String k = key("ttl");
        store().set(k, "v", 1);
        assertTrue(store().get(k).isPresent());
        pass(1.5);
        assertTrue(store().get(k).isEmpty());
    }

    @Test void setIfAbsentIsALock() throws Exception {
        String k = key("lock");
        assertTrue(store().setIfAbsent(k, "1", 1), "first caller gets the lock");
        assertFalse(store().setIfAbsent(k, "2", 1), "second caller does not");
        assertEquals("1", store().get(k).orElseThrow());
        pass(1.5);
        assertTrue(store().setIfAbsent(k, "3", 1), "an expired lock can be taken again");
    }

    @Test void deleteReleasesALock() {
        String k = key("rel");
        assertTrue(store().setIfAbsent(k, "1", 30));
        store().delete(k);
        assertTrue(store().setIfAbsent(k, "1", 30));
    }

    @Test void incrCountsFromOne() {
        String k = key("n");
        assertEquals(1, store().incr(k, 60));
        assertEquals(2, store().incr(k, 60));
        assertEquals(3, store().incr(k, 60));
    }

    @Test void incrSetsTheExpiryOnlyWhenTheCounterIsCreated() throws Exception {
        String k = key("budget");
        store().incr(k, 2);
        pass(1.2);
        assertEquals(2, store().incr(k, 100), "a later call must not extend the window");
        pass(1.2);
        assertEquals(1, store().incr(k, 2), "after expiry the counter starts again");
    }

    @Test void incrIsAtomicUnderConcurrency() throws Exception {
        String k = key("race");
        var threads = new Thread[8];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> { for (int j = 0; j < 25; j++) store().incr(k, 60); });
            threads[i].start();
        }
        for (Thread t : threads) t.join();
        assertEquals(201, store().incr(k, 60));
    }

    @Test void deleteByPrefixOnlyTouchesThatPrefix() {
        String p = key("p") + ":";
        store().set(p + "a", "1", 60);
        store().set(p + "b", "1", 60);
        store().set(p + "*odd?[name]", "1", 60);
        String other = key("keep");
        store().set(other, "1", 60);
        store().deleteByPrefix(p);
        assertTrue(store().get(p + "a").isEmpty());
        assertTrue(store().get(p + "b").isEmpty());
        assertTrue(store().get(p + "*odd?[name]").isEmpty());
        assertTrue(store().get(other).isPresent());
    }
}
