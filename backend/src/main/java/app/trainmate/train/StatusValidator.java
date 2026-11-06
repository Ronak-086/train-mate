package app.trainmate.train;

import app.trainmate.util.Ist;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Sanity checks on a provider's answer before we believe it (spec 8.7). Returns the reason it was rejected, or empty
 * if it is acceptable. A rejected answer is logged and ignored; the previous data stays.
 */
public final class StatusValidator {
    static final int MIN_DELAY = -60;
    static final int MAX_DELAY = 24 * 60;
    static final int MAX_JUMP_MIN = 120;
    static final Duration JUMP_WINDOW = Duration.ofMinutes(30);
    static final Duration MAX_OBSERVATION_AGE = Duration.ofHours(6);
    static final Duration FUTURE_LEEWAY = Duration.ofSeconds(60);

    public Optional<String> check(List<RouteStop> stops, CachedStatus prev, ProviderStatus c, Instant now) {
        int idx = indexOf(stops, c.lastStationCode());
        if (idx < 0) return Optional.of("station " + c.lastStationCode() + " is not on this train's route");
        if (c.delayMinutes() < MIN_DELAY || c.delayMinutes() > MAX_DELAY) return Optional.of("delay of " + c.delayMinutes() + " minutes is out of range");
        if (c.observedAt() == null) return Optional.of("no observation time");
        if (c.observedAt().isAfter(now.plus(FUTURE_LEEWAY))) return Optional.of("observed in the future");
        if (Duration.between(c.observedAt(), now).compareTo(MAX_OBSERVATION_AGE) > 0) return Optional.of("observation is more than 6 hours old");

        // Compare with what we already believe, but only if that was learned on this journey day.
        if (prev != null && prev.observedAt().isAfter(Ist.startOfDay(c.journeyDate()))) {
            int prevIdx = indexOf(stops, prev.lastStationCode());
            if (prevIdx > idx) return Optional.of("train appears to have moved backwards, from " + prev.lastStationCode() + " to " + c.lastStationCode());
            boolean recent = Duration.between(prev.fetchedAt(), now).compareTo(JUMP_WINDOW) <= 0;
            if (recent && Math.abs(c.delayMinutes() - prev.delayMinutes()) > MAX_JUMP_MIN) return Optional.of("delay jumped by more than " + MAX_JUMP_MIN + " minutes");
        }
        return Optional.empty();
    }

    private static int indexOf(List<RouteStop> stops, String code) {
        for (int i = 0; i < stops.size(); i++) if (stops.get(i).code().equals(code)) return i;
        return -1;
    }

}
