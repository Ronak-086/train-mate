package app.trainmate.train;

import app.trainmate.db.Db;
import app.trainmate.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalTime;

/**
 * Loads stations, trains and their stops from a JSON file (spec 8.2). Safe to run repeatedly: stations and trains are
 * upserted and a train's stops are replaced wholesale in one transaction, so a bad file never leaves half a timetable.
 */
public final class ScheduleImporter {
    private final Db db;

    public ScheduleImporter(Db db) { this.db = db; }

    public int importJson(InputStream in) throws IOException {
        JsonNode root = Json.tree(in.readAllBytes());
        JsonNode trains = root.path("trains");
        return db.tx(tx -> {
            for (JsonNode s : root.path("stations")) {
                tx.update("INSERT INTO stations (code, name) VALUES (?, ?) ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name",
                    s.path("code").asText(), s.path("name").asText());
            }
            int count = 0;
            for (JsonNode t : trains) {
                JsonNode stops = t.path("stops");
                if (!stops.isArray() || stops.size() < 2) throw new IllegalArgumentException("Train " + t.path("trainNo").asText() + " needs at least two stops");
                String no = t.path("trainNo").asText();
                tx.update("""
                    INSERT INTO trains (train_no, name, from_stn, to_stn, run_days) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (train_no) DO UPDATE SET name = EXCLUDED.name, from_stn = EXCLUDED.from_stn,
                      to_stn = EXCLUDED.to_stn, run_days = EXCLUDED.run_days
                    """, no, t.path("name").asText(), stops.get(0).path("station").asText(),
                    stops.get(stops.size() - 1).path("station").asText(), t.hasNonNull("runDays") ? t.get("runDays").asText() : null);
                tx.update("DELETE FROM train_schedule WHERE train_no = ?", no);
                int seq = 1;
                for (JsonNode st : stops) {
                    tx.update("INSERT INTO train_schedule (train_no, seq, station_code, sched_arr, sched_dep, day_offset) VALUES (?, ?, ?, ?, ?, ?)",
                        no, seq++, st.path("station").asText(), time(st, "arr"), time(st, "dep"), st.path("dayOffset").asInt(0));
                }
                count++;
            }
            return count;
        });
    }

    private static LocalTime time(JsonNode n, String field) { return n.hasNonNull(field) ? LocalTime.parse(n.get(field).asText()) : null; }
}
