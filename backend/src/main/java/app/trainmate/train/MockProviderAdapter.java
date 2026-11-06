package app.trainmate.train;

import app.trainmate.util.Ist;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * A pretend provider for development and tests. It "tracks" only trains it has a scripted delay for, and works out where
 * the train is from the timetable and the clock, so the status moves on as time passes.
 */
public final class MockProviderAdapter implements ProviderAdapter {
    private final Clock clock;
    private final TrainRepository trains;
    private final Map<String, Integer> delays;

    /** @param delays scripted delays keyed "trainNo:YYYY-MM-DD" */
    public MockProviderAdapter(Clock clock, TrainRepository trains, Map<String, Integer> delays) {
        this.clock = clock;
        this.trains = trains;
        this.delays = delays;
    }

    @Override public String name() { return "mock"; }

    @Override
    public ProviderStatus fetch(String trainNo, LocalDate date) throws ProviderException {
        Integer delay = delays.get(trainNo + ":" + date);
        if (delay == null) throw new ProviderException("No live data for " + trainNo + " on " + date);
        List<RouteStop> stops = trains.route(trainNo);
        Instant now = clock.instant();
        RouteStop last = null;
        Instant crossed = null;
        for (RouteStop s : stops) {
            Instant at = StatusMath.sched(date, s).plus(Duration.ofMinutes(delay));
            if (!at.isAfter(now)) {
                last = s;
                crossed = at;
            }
        }
        if (last == null) throw new ProviderException(trainNo + " has not started yet");
        return new ProviderStatus(trainNo, date, last.code(), delay, crossed, Map.of("mock", true, "observed", Ist.iso(crossed)));
    }
}
