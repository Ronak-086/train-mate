package app.trainmate.train;

import app.trainmate.db.Db;
import app.trainmate.train.Dtos.StationDto;
import app.trainmate.train.Dtos.TrainDto;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

public final class TrainRepository {
    public record TrainInfo(String trainNo, String name, StationDto from, StationDto to, String runDays) {
        public TrainDto dto() { return new TrainDto(trainNo, name, from, to); }

        /** run_days is seven characters starting Monday; '-' marks a day the train does not run. Null means every day. */
        public boolean runsOn(LocalDate date) {
            if (runDays == null || runDays.length() < 7) return true;
            char c = runDays.charAt(date.getDayOfWeek().getValue() - 1);
            return c != '-' && c != ' ';
        }
    }

    private static final String TRAIN = """
        SELECT t.train_no, t.name, t.from_stn, f.name AS from_name, t.to_stn, o.name AS to_name, t.run_days
        FROM trains t
        JOIN stations f ON f.code = t.from_stn
        JOIN stations o ON o.code = t.to_stn
        """;

    private final Db db;

    public TrainRepository(Db db) { this.db = db; }

    private static TrainInfo map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TrainInfo(rs.getString("train_no"), rs.getString("name"),
            new StationDto(rs.getString("from_stn"), rs.getString("from_name")),
            new StationDto(rs.getString("to_stn"), rs.getString("to_name")), rs.getString("run_days"));
    }

    /** By number prefix or name, case-insensitive. strpos avoids LIKE wildcards in what people type. */
    public List<TrainInfo> search(String q) {
        return db.list(TRAIN + " WHERE starts_with(t.train_no, ?) OR strpos(lower(t.name), lower(?)) > 0 "
            + "OR strpos(lower(t.train_no || ' ' || t.name), lower(?)) > 0 ORDER BY t.train_no LIMIT 10", TrainRepository::map, q, q, q);
    }

    public TrainInfo find(String trainNo) { return db.one(TRAIN + " WHERE t.train_no = ?", TrainRepository::map, trainNo); }

    public List<RouteStop> route(String trainNo) {
        return db.list("""
            SELECT s.seq, s.station_code, st.name, s.sched_arr, s.sched_dep, s.day_offset
            FROM train_schedule s JOIN stations st ON st.code = s.station_code
            WHERE s.train_no = ? ORDER BY s.seq
            """, rs -> new RouteStop(rs.getInt("seq"), rs.getString("station_code"), rs.getString("name"),
                rs.getObject("sched_arr", LocalTime.class), rs.getObject("sched_dep", LocalTime.class), rs.getInt("day_offset")), trainNo);
    }
}
