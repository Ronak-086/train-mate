package app.trainmate.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The production host. All behaviour lives in the framework-free core (everything outside this package); Spring supplies the
 * web server, the connection pool, Flyway migrations and configuration. NOT compiled in the environment this was written in
 * (no access to Maven Central), so run `mvn verify` first.
 */
@SpringBootApplication
public class TrainMateApplication {
    public static void main(String[] args) {
        SpringApplication.run(TrainMateApplication.class, args);
    }
}
