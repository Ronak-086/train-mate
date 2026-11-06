package app.trainmate;

import app.trainmate.db.Db;
import app.trainmate.train.ScheduleImporter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/** A real PostgreSQL for tests (TEST_DB_URL, default localhost/trainmate_test). Tests skip when it is unreachable. */
public final class TestDb {
    public static final String URL = System.getenv().getOrDefault("TEST_DB_URL", "jdbc:postgresql://localhost:5432/trainmate_test");
    static final String USER = System.getenv().getOrDefault("TEST_DB_USER", "trainmate");
    static final String PASS = System.getenv().getOrDefault("TEST_DB_PASSWORD", "trainmate");
    private static boolean migrated = false;

    private TestDb() {}

    public static boolean available() {
        try (Connection c = DriverManager.getConnection(URL, USER, PASS)) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }

    public static DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(URL);
        ds.setUser(USER);
        ds.setPassword(PASS);
        return ds;
    }

    /** Fresh schema built from the real migration files, plus the timetable fixtures. Done once per JVM. */
    public static synchronized Db migratedDb() throws Exception {
        Db db = new Db(dataSource());
        if (!migrated) {
            try (Connection c = dataSource().getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;");
                for (String file : new String[] {"V1__init.sql", "V2__contract_additions.sql", "V3__delete_behaviour.sql"}) {
                    try (InputStream in = TestDb.class.getResourceAsStream("/db/migration/" + file)) {
                        st.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            migrated = true;
        }
        clean(db);
        return db;
    }

    /** A COUNT(*) query as a primitive int, so it can go straight into assertEquals. */
    public static int count(Db db, String sql) {
        Integer n = db.one(sql, rs -> rs.getInt(1));
        return n == null ? 0 : n;
    }

    /** Removes everything a test creates, keeping the timetable. */
    public static void clean(Db db) throws Exception {
        db.update("TRUNCATE users, train_status_snapshot, api_usage RESTART IDENTITY CASCADE");
        db.update("TRUNCATE train_schedule, trains, stations CASCADE");
        try (InputStream in = TestDb.class.getResourceAsStream("/fixtures/schedule.json")) {
            new ScheduleImporter(db).importJson(in);
        }
    }
}
