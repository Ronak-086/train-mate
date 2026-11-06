package app.trainmate.train;

import app.trainmate.train.Dtos.StationDto;
import java.time.LocalTime;

/** One stop on a train's route, with scheduled times as wall-clock IST and a day offset for overnight trains. */
public record RouteStop(int seq, String code, String name, LocalTime arr, LocalTime dep, int dayOffset) {
    /** Arrival, or departure at the origin. */
    public LocalTime sched() { return arr != null ? arr : dep; }

    public StationDto station() { return new StationDto(code, name); }
}
