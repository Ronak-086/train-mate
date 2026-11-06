package app.trainmate.spring;

import app.trainmate.Wiring;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Turns Spring's configuration into the core's Settings and builds the application from them. */
@Configuration
public class AppConfig {
    @Bean
    Wiring.Settings settings(Environment env) {
        return new Wiring.Settings(
            env.getProperty("trainmate.auth.mode", "firebase"),
            env.getProperty("trainmate.firebase.project-id", ""),
            env.getProperty("trainmate.pnr-salt", ""),
            env.getProperty("trainmate.redis.url", ""),
            env.getProperty("trainmate.provider.name", "railradar"),
            env.getProperty("trainmate.provider.api-key", ""),
            env.getProperty("trainmate.provider.daily-budget", Integer.class, 100),
            env.getProperty("trainmate.dev-tools", Boolean.class, false));
    }

    /** Refuses to start (with a clear message) on unsafe settings, e.g. dev sign-in without dev tools. */
    @Bean
    Wiring.App trainMateApp(Wiring.Settings settings, DataSource dataSource) {
        return Wiring.build(settings, dataSource);
    }
}
