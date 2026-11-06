package app.trainmate;

import app.trainmate.http.JdkServer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Runs the API without Spring Boot, for local development and for testing with the web app.
 *
 *   DB_URL=jdbc:postgresql://localhost:5432/trainmate DB_USER=... DB_PASSWORD=... AUTH_MODE=dev DEV_TOOLS=1 PORT=8081 \
 *   MIGRATE=1 java -cp ... app.trainmate.JdkMain
 *
 * MIGRATE=1 applies the SQL migrations to an EMPTY database. Real deployments use Flyway (via Spring Boot) instead.
 */
public final class JdkMain {
    private JdkMain() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(env.getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/trainmate"));
        ds.setUser(env.getOrDefault("DB_USER", "trainmate"));
        ds.setPassword(env.getOrDefault("DB_PASSWORD", "trainmate"));
        if ("1".equals(env.get("MIGRATE"))) migrateIfEmpty(ds);

        Wiring.Settings settings = Wiring.Settings.fromEnv(env);
        Wiring.App app = Wiring.build(settings, ds);
        int port = Integer.parseInt(env.getOrDefault("PORT", "8081"));
        JdkServer server = new JdkServer(app.handler(), port, settings.devTools() ? app.clock() : null).start();
        System.out.println("Train Mate API listening on http://localhost:" + server.port() + " (auth=" + settings.authMode() + ", devTools=" + settings.devTools() + ")");
        if (settings.devTools()) {
            // Start from the seed data so the web app has someone to sign in as.
            java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/dev/reset"))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.discarding());
            System.out.println("Seed data loaded (dev reset).");
        }
    }

    private static void migrateIfEmpty(PGSimpleDataSource ds) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            var rs = st.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'users'");
            rs.next();
            if (rs.getInt(1) > 0) return;
            for (String file : new String[] {"V1__init.sql", "V2__contract_additions.sql", "V3__delete_behaviour.sql"}) {
                try (InputStream in = JdkMain.class.getResourceAsStream("/db/migration/" + file)) {
                    st.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            System.out.println("Applied migrations.");
        }
    }
}
