package app.trainmate.trip;

import app.trainmate.http.ApiException;
import app.trainmate.http.Body;
import app.trainmate.train.RouteStop;
import app.trainmate.train.TrainRepository;
import app.trainmate.trip.TripDtos.TripDto;
import app.trainmate.user.UserService;
import app.trainmate.util.Ist;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public final class TripService {
    public static final List<String> CLASSES = List.of("SL", "3A", "2A", "1A", "CC", "2S", "3E");
    public static final List<String> BERTHS = List.of("LOWER", "MIDDLE", "UPPER", "SIDE_LOWER", "SIDE_UPPER", "WINDOW");
    private static final Pattern COACH = Pattern.compile("^[A-Z]{1,2}\\d{1,2}$");
    private static final Pattern PNR = Pattern.compile("^\\d{10}$");

    private final TripRepository trips;
    private final TrainRepository trains;
    private final Clock clock;
    private final String pnrSalt;

    public TripService(TripRepository trips, TrainRepository trains, Clock clock, String pnrSalt) {
        this.trips = trips;
        this.trains = trains;
        this.clock = clock;
        this.pnrSalt = pnrSalt;
    }

    public TripDto create(UUID userId, Body b) {
        String trainNo = b.str("trainNo", 1, 5, true);
        List<RouteStop> stops = trains.route(trainNo);
        if (stops.isEmpty()) throw ApiException.notFound("TRAIN_NOT_FOUND", "We don't have that train.");

        LocalDate date = Body.date(b.raw("journeyDate"), "journeyDate");
        LocalDate today = Ist.date(clock.instant());
        if (date.isBefore(today.minusDays(1)) || date.isAfter(today.plusDays(120))) throw ApiException.bad("journeyDate must be from yesterday to 120 days ahead.");

        String boarding = b.str("boardingStn", 1, 6, true);
        String dest = b.str("destStn", 1, 6, true);
        int bi = indexOf(stops, boarding);
        int di = indexOf(stops, dest);
        if (bi < 0 || di < 0) throw ApiException.bad("STATION_NOT_ON_ROUTE", "Both stations must be stops on this train's route.");
        if (bi >= di) throw ApiException.bad("STATION_ORDER", "Your destination must come after your boarding station.");

        String travelClass = b.oneOf("travelClass", CLASSES, true);
        String coach = b.str("coach", 1, 3, true).toUpperCase(java.util.Locale.ROOT);
        if (!COACH.matcher(coach).matches()) throw ApiException.bad("Coach looks like B3, S5 or A1.");
        Integer seat = b.integer("seatNo", 1, 200, false);
        String berth = b.oneOf("berthType", BERTHS, false);
        String pnr = b.str("pnr", 10, 10, false);
        if (pnr != null && !PNR.matcher(pnr).matches()) throw ApiException.bad("PNR is 10 digits.");

        if (trips.exists(userId, trainNo, date)) throw ApiException.conflict("TRIP_EXISTS", "You've already added this train for that date.");
        try {
            UUID id = trips.insert(userId, trainNo, date, boarding, dest, travelClass, coach, seat, berth, pnr == null ? null : hash(pnr));
            return trips.byId(id);
        } catch (app.trainmate.db.Db.DbException e) {
            if (e.isUniqueViolation()) throw ApiException.conflict("TRIP_EXISTS", "You've already added this train for that date.");
            throw e;
        }
    }

    public List<TripDto> mine(UUID userId) { return trips.forUser(userId); }

    public void delete(UUID userId, String id) {
        UUID uuid = UserService.parseId(id);
        if (uuid == null || !trips.delete(userId, uuid)) throw ApiException.notFound("TRIP_NOT_FOUND", "Trip not found.");
    }

    /** Salted SHA-256. The raw PNR is never stored or logged. */
    private String hash(String pnr) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((pnrSalt + ":" + pnr).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int indexOf(List<RouteStop> stops, String code) {
        for (int i = 0; i < stops.size(); i++) if (stops.get(i).code().equals(code)) return i;
        return -1;
    }
}
