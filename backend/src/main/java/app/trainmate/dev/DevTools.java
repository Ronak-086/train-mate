package app.trainmate.dev;

import app.trainmate.db.Db;
import app.trainmate.http.ApiException;
import app.trainmate.store.KeyValueStore;
import app.trainmate.train.ProviderException;
import app.trainmate.train.ScheduleImporter;
import app.trainmate.train.StatusService;
import app.trainmate.trip.TripRepository;
import app.trainmate.user.UserRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Development and test helpers behind /api/dev: reset to the seed data and move the clock. Only wired up when
 * dev tools are explicitly enabled; never in production.
 */
public final class DevTools {
    /** The moment the supplied designs depict: 09:06 IST on 25 Oct 2026. */
    public static final Instant DESIGN_MOMENT = Instant.parse("2026-10-25T03:36:00Z");
    private static final String[][] USERS = {
        {"u_me", "Asha K."}, {"u_priya", "Priya S."}, {"u_arun", "Arun D."}, {"u_meena", "Meena R."}, {"u_kavya", "Kavya N."},
        {"u_dev", "Dev P."}, {"u_imran", "Imran Q."}, {"u_rahul", "Rahul V."}, {"u_sana", "Sana M."}};
    // user, coach, seat, berth. Everyone is on train 12345 on 25 Oct 2026.
    private static final String[][] TRIPS = {
        {"u_me", "B3", "34", "UPPER"}, {"u_priya", "B3", "33", "LOWER"}, {"u_arun", "B3", "12", "LOWER"}, {"u_meena", "B3", "40", "UPPER"},
        {"u_kavya", "B3", "7", "MIDDLE"}, {"u_dev", "B3", "51", "SIDE_LOWER"}, {"u_imran", "B3", "22", "LOWER"}, {"u_rahul", "B4", "20", "LOWER"},
        {"u_sana", "B6", "9", "UPPER"}};

    private final Db db;
    private final MutableClock clock;
    private final KeyValueStore kv;
    private final StatusService status;
    private final UserRepository users;
    private final TripRepository trips;

    public DevTools(Db db, MutableClock clock, KeyValueStore kv, StatusService status, UserRepository users, TripRepository trips) {
        this.db = db;
        this.clock = clock;
        this.kv = kv;
        this.status = status;
        this.users = users;
        this.trips = trips;
    }

    /** Serialised: two overlapping resets (e.g. server start-up and a test) would collide on the seed trips. */
    public synchronized void reset() {
        try {
            db.update("TRUNCATE users, train_status_snapshot, api_usage RESTART IDENTITY CASCADE");
            try (InputStream in = DevTools.class.getResourceAsStream("/fixtures/schedule.json")) {
                new ScheduleImporter(db).importJson(in);
            }
            for (String p : new String[] {"status:", "recent:", "lock:status:", "budget:"}) kv.deleteByPrefix(p);
            clock.runFrom(DESIGN_MOMENT);

            LocalDate date = LocalDate.parse("2026-10-25");
            for (String[] u : USERS) users.findOrCreate("dev:" + u[0], u[0].substring(2) + "@example.test", u[1]);
            for (String[] t : TRIPS) {
                var me = users.findOrCreate("dev:" + t[0], null, t[0]);
                trips.insert(me.id(), "12345", date, "GHY", "NDLS", "3A", t[1], Integer.valueOf(t[2]), t[3], null);
            }
            status.prime("12345", date, Duration.ofMinutes(4)); // "updated 4 min ago", as in the Home design
        } catch (IOException | ProviderException e) {
            throw new IllegalStateException("Dev reset failed: " + e.getMessage(), e);
        }
    }

    /** Freezes the clock at {@code iso}, or lets it run on from the design moment when null. Returns the new "now". */
    public String setClock(String iso) {
        if (iso == null) {
            clock.runFrom(DESIGN_MOMENT);
        } else {
            try {
                clock.freezeAt(OffsetDateTime.parse(iso).toInstant());
            } catch (DateTimeParseException e) {
                throw ApiException.bad("iso must be an ISO-8601 time with an offset, like 2026-10-25T09:06:00+05:30.");
            }
        }
        return app.trainmate.util.Ist.iso(clock.instant());
    }
}
