package app.trainmate.trip;

import app.trainmate.db.Db;
import app.trainmate.train.Dtos.StationDto;
import app.trainmate.trip.TripDtos.TripDto;
import app.trainmate.util.Ist;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class TripRepository {
    private static final String SELECT = """
        SELECT t.id, t.train_no, tr.name AS train_name, t.journey_date, t.boarding_stn, b.name AS boarding_name, t.dest_stn, d.name AS dest_name,
               t.travel_class, t.coach, t.seat_no, t.berth_type, t.created_at
        FROM trips t
        JOIN trains tr ON tr.train_no = t.train_no
        JOIN stations b ON b.code = t.boarding_stn
        JOIN stations d ON d.code = t.dest_stn
        """;

    private final Db db;

    public TripRepository(Db db) { this.db = db; }

    private static TripDto map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TripDto(rs.getObject("id", UUID.class).toString(), rs.getString("train_no"), rs.getString("train_name"),
            rs.getObject("journey_date", LocalDate.class).toString(), new StationDto(rs.getString("boarding_stn"), rs.getString("boarding_name")),
            new StationDto(rs.getString("dest_stn"), rs.getString("dest_name")), rs.getString("travel_class"), rs.getString("coach"),
            Db.integer(rs, "seat_no"), rs.getString("berth_type"), Ist.iso(Db.instant(rs, "created_at")));
    }

    public List<TripDto> forUser(UUID userId) {
        return db.list(SELECT + " WHERE t.user_id = ? ORDER BY t.journey_date DESC, t.created_at DESC", TripRepository::map, userId);
    }

    public TripDto byId(UUID id) { return db.one(SELECT + " WHERE t.id = ?", TripRepository::map, id); }

    public boolean exists(UUID userId, String trainNo, LocalDate date) {
        return db.one("SELECT 1 FROM trips WHERE user_id = ? AND train_no = ? AND journey_date = ?", rs -> 1, userId, trainNo, date) != null;
    }

    /** True if anyone has this train and date saved. Tells the status service the data is worth refreshing. */
    public boolean anyoneOn(String trainNo, LocalDate date) {
        return db.one("SELECT 1 FROM trips WHERE train_no = ? AND journey_date = ? LIMIT 1", rs -> 1, trainNo, date) != null;
    }

    public UUID insert(UUID userId, String trainNo, LocalDate date, String boarding, String dest, String travelClass, String coach,
                       Integer seatNo, String berthType, String pnrHash) {
        return db.one("""
            INSERT INTO trips (user_id, train_no, journey_date, boarding_stn, dest_stn, travel_class, coach, seat_no, berth_type, pnr_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """, rs -> rs.getObject(1, UUID.class), userId, trainNo, date, boarding, dest, travelClass, coach, seatNo, berthType, pnrHash);
    }

    /** Deleting a trip removes its posts too (ON DELETE CASCADE). */
    public boolean delete(UUID userId, UUID id) { return db.update("DELETE FROM trips WHERE id = ? AND user_id = ?", id, userId) > 0; }

    /** Today's trip, else the nearest by date; ties go to the later date. */
    public TripDto active(UUID userId, LocalDate today) {
        return db.one(SELECT + " WHERE t.user_id = ? ORDER BY abs(t.journey_date - ?::date), t.journey_date DESC, t.created_at LIMIT 1",
            TripRepository::map, userId, today);
    }
}
