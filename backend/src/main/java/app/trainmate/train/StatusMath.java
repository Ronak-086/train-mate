package app.trainmate.train;

import app.trainmate.train.Dtos.EtaDto;
import app.trainmate.train.Dtos.TrainStatusDto;
import app.trainmate.util.Ist;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Turns "where the train was last seen and how late" into what the app shows: a delay range, and an arrival window for
 * the next station and the destination. Pure functions of their inputs, so the numbers are easy to test.
 *
 * Delay range: delay plus or minus r, rounded to 5 minutes. r depends on how recently we confirmed the data
 * (under 10 minutes: 3, up to 30: 5, older: 10). Arrival window: scheduled time plus the low delay, up to scheduled
 * plus the high delay plus 10 minutes for stops still ahead.
 */
public final class StatusMath {
    static final int WINDOW_SLACK_MIN = 10;
    private static final String NO_LIVE = "Timetable only. Live position isn't available for this train.";

    private StatusMath() {}

    static int round5(double n) { return (int) Math.round(n / 5.0) * 5; }

    /**
     * "10–15 min late" when the whole range is late, "up to 20 min late" when old data makes the range reach zero
     * (so a train that is 12 minutes late never reads "on time"), and "On time" only when the delay itself is zero.
     */
    static String delayLabel(int delay, int low, int high) {
        if (delay > 0) return low > 0 ? low + "–" + high + " min late" : "up to " + high + " min late";
        if (delay < 0) return high < 0 ? -high + "–" + -low + " min early" : "up to " + -low + " min early";
        return "On time";
    }

    /** @param cached what we know about the train, or null if nothing live */
    public static TrainStatusDto build(String trainNo, LocalDate date, List<RouteStop> stops, CachedStatus cached, boolean stale, Instant now) {
        int last = stops.size() - 1;
        int lastIdx = -1;
        if (cached != null) {
            for (int i = 0; i <= last; i++) if (stops.get(i).code().equals(cached.lastStationCode())) lastIdx = i;
        }
        if (cached == null || lastIdx < 0) return scheduleOnly(trainNo, date, stops, now, NO_LIVE);

        int delay = cached.delayMinutes();
        double ageMin = Duration.between(cached.fetchedAt(), now).toMillis() / 60_000.0;
        int r = ageMin < 10 ? 3 : ageMin <= 30 ? 5 : 10;
        int low = round5(delay - r);
        int high = round5(delay + r);
        if (high <= low) high = low + 5;
        final int lo = low, hi = high;

        boolean arrived = lastIdx == last;
        EtaDto next = arrived ? null : eta(date, stops.get(lastIdx + 1), lo, hi + WINDOW_SLACK_MIN);
        EtaDto dest = eta(date, stops.get(last), lo, hi + WINDOW_SLACK_MIN);
        String notice = arrived ? "Arrived at " + stops.get(last).name() + "." : stale ? "Updating. This may be a few minutes out of date." : null;

        return new TrainStatusDto(trainNo, date.toString(), "PROVIDER", stops.get(lastIdx).station(), Ist.iso(cached.observedAt()),
            Ist.iso(cached.fetchedAt()), delay, lo, hi, delayLabel(delay, lo, hi), next, dest, stale, notice);
    }

    /** No live data: every time is just the timetable. */
    public static TrainStatusDto scheduleOnly(String trainNo, LocalDate date, List<RouteStop> stops, Instant now, String notice) {
        EtaDto next = null;
        for (RouteStop s : stops) {
            if (sched(date, s).isAfter(now)) {
                next = eta(date, s, 0, 0);
                break;
            }
        }
        return new TrainStatusDto(trainNo, date.toString(), "SCHEDULE", null, null, null, null, null, null, null, next,
            eta(date, stops.get(stops.size() - 1), 0, 0), false, notice);
    }

    static Instant sched(LocalDate date, RouteStop s) { return Ist.at(date, s.sched(), s.dayOffset()); }

    private static EtaDto eta(LocalDate date, RouteStop s, int lowMin, int highMin) {
        Instant t = sched(date, s);
        return new EtaDto(s.station(), Ist.iso(t), Ist.iso(t.plus(Duration.ofMinutes(lowMin))), Ist.iso(t.plus(Duration.ofMinutes(highMin))));
    }
}
