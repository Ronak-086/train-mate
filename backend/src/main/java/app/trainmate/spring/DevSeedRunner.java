package app.trainmate.spring;

import app.trainmate.Wiring;
import app.trainmate.http.Req;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** With trainmate.dev-seed=true (and dev tools on), loads the seed travellers at start-up so the web app has someone to sign in as. */
@Component
class DevSeedRunner implements ApplicationRunner {
    private final Wiring.App app;
    private final boolean seed;

    DevSeedRunner(Wiring.App app, @Value("${trainmate.dev-seed:false}") boolean seed) {
        this.app = app;
        this.seed = seed;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seed) return;
        var res = app.handler().handle(new Req("POST", "/api/dev/reset", Map.of(), Map.of(), new byte[0]));
        if (res.status() != 204) throw new IllegalStateException("Dev seed failed with status " + res.status() + " (is DEV_TOOLS=true?)");
    }
}
