package app.trainmate.train;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Stops calling a failing provider for a while (spec 8.3). After {@code threshold} failures in a row it opens and
 * refuses calls; once {@code openFor} has passed it lets exactly one probe through. A success closes it, a failure reopens it.
 */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final Clock clock;
    private final int threshold;
    private final Duration openFor;
    private State state = State.CLOSED;
    private int failures = 0;
    private Instant openUntil = Instant.MIN;
    private boolean probing = false;

    public CircuitBreaker(Clock clock, int threshold, Duration openFor) {
        this.clock = clock;
        this.threshold = threshold;
        this.openFor = openFor;
    }

    public synchronized boolean allow() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (clock.instant().isBefore(openUntil)) return false;
                state = State.HALF_OPEN;
                probing = true;
                return true;
            default: // HALF_OPEN: one probe at a time
                if (probing) return false;
                probing = true;
                return true;
        }
    }

    public synchronized void success() {
        state = State.CLOSED;
        failures = 0;
        probing = false;
    }

    public synchronized void failure() {
        probing = false;
        if (state == State.HALF_OPEN || ++failures >= threshold) {
            state = State.OPEN;
            openUntil = clock.instant().plus(openFor);
        }
    }

    public synchronized State state() { return state; }
}
