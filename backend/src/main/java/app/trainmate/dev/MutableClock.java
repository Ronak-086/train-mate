package app.trainmate.dev;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests and the development server can freeze, set, or let run from a chosen moment. */
public final class MutableClock extends Clock {
    private volatile Instant frozen;
    private volatile Instant base;
    private volatile long startedNanos;

    /** Runs in real time starting from {@code start}. */
    public MutableClock(Instant start) { runFrom(start); }

    public void runFrom(Instant start) {
        this.frozen = null;
        this.base = start;
        this.startedNanos = System.nanoTime();
    }

    public void freezeAt(Instant t) { this.frozen = t; }

    public void advance(Duration d) { freezeAt(instant().plus(d)); }

    @Override public Instant instant() {
        Instant f = frozen;
        return f != null ? f : base.plusNanos(System.nanoTime() - startedNanos);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
}
