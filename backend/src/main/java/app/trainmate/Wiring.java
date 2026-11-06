package app.trainmate;

import app.trainmate.auth.DevTokenVerifier;
import app.trainmate.auth.FirebaseTokenVerifier;
import app.trainmate.auth.GoogleJwksSource;
import app.trainmate.auth.JwksSource;
import app.trainmate.auth.TokenVerifier;
import app.trainmate.chat.ChatService;
import app.trainmate.chat.ConversationRepository;
import app.trainmate.chat.MessageRepository;
import app.trainmate.db.Db;
import app.trainmate.dev.DevTools;
import app.trainmate.dev.MutableClock;
import app.trainmate.http.ApiHandler;
import app.trainmate.post.PostRepository;
import app.trainmate.post.PostService;
import app.trainmate.safety.ModerationRepository;
import app.trainmate.safety.ModerationService;
import app.trainmate.store.InMemoryKeyValueStore;
import app.trainmate.store.KeyValueStore;
import app.trainmate.store.RedisKeyValueStore;
import app.trainmate.thread.CommentRepository;
import app.trainmate.thread.ReactionRepository;
import app.trainmate.thread.ThreadService;
import app.trainmate.train.BudgetGuard;
import app.trainmate.train.CircuitBreaker;
import app.trainmate.train.MockProviderAdapter;
import app.trainmate.train.ProviderAdapter;
import app.trainmate.train.RailRadarProviderAdapter;
import app.trainmate.train.RapidApiProviderAdapter;
import app.trainmate.train.SnapshotRepository;
import app.trainmate.train.StatusService;
import app.trainmate.train.StatusValidator;
import app.trainmate.train.TrainRepository;
import app.trainmate.trip.TripRepository;
import app.trainmate.trip.TripService;
import app.trainmate.user.UserRepository;
import app.trainmate.user.UserService;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.sql.DataSource;

/** Builds the application from settings. Spring and the plain JDK server both call this, so they behave identically. */
public final class Wiring {
    /**
     * @param authMode          "firebase" (production) or "dev" (accepts dev:<name> tokens; needs devTools)
     * @param firebaseProjectId required when authMode is "firebase"
     * @param redisUrl          redis:// or rediss:// URL; blank means an in-process store
     * @param provider          which live-data provider to use; "mock" or "rapidapi"
     * @param rapidApiKey       API key for rapidapi provider (if configured)
     * @param devTools          enables /api/dev/reset and /api/dev/clock and a movable clock. Never in production.
     */
    public record Settings(String authMode, String firebaseProjectId, String pnrSalt, String redisUrl, String provider,
                           String rapidApiKey, int dailyProviderBudget, boolean devTools) {
        public Settings(String authMode, String firebaseProjectId, String pnrSalt, String redisUrl, String provider,
                        int dailyProviderBudget, boolean devTools) {
            this(authMode, firebaseProjectId, pnrSalt, redisUrl, provider, "", dailyProviderBudget, devTools);
        }

        public static Settings fromEnv(Map<String, String> env) {
            String apiKey = env.getOrDefault("RAILRADAR_API_KEY",
                env.getOrDefault("TRAIN_API_KEY", env.getOrDefault("RAPIDAPI_KEY", "")));
            return new Settings(
                env.getOrDefault("AUTH_MODE", "firebase"),
                env.get("FIREBASE_PROJECT_ID"),
                env.getOrDefault("PNR_SALT", ""),
                env.get("REDIS_URL"),
                env.getOrDefault("PROVIDER", "railradar"),
                apiKey,
                Integer.parseInt(env.getOrDefault("PROVIDER_DAILY_BUDGET", "100")),
                "1".equals(env.get("DEV_TOOLS")) || "true".equalsIgnoreCase(env.get("DEV_TOOLS"))
            );
        }
    }

    public record App(ApiHandler handler, Clock clock, KeyValueStore store) {}

    private Wiring() {}

    public static App build(Settings s, DataSource ds) {
        return build(s, ds, null);
    }

    /** @param jwksOverride where to find Firebase's public keys. Null means Google's published keys; tests pass their own. */
    public static App build(Settings s, DataSource ds, JwksSource jwksOverride) {
        // The checks that keep a development convenience from becoming a hole in production.
        if ("dev".equals(s.authMode()) && !s.devTools()) throw new IllegalStateException("AUTH_MODE=dev is only allowed together with DEV_TOOLS=1. Never use it in production.");
        if ("firebase".equals(s.authMode()) && (s.firebaseProjectId() == null || s.firebaseProjectId().isBlank())) throw new IllegalStateException("FIREBASE_PROJECT_ID is required for AUTH_MODE=firebase.");
        if (!"dev".equals(s.authMode()) && !"firebase".equals(s.authMode())) throw new IllegalStateException("Unknown AUTH_MODE: " + s.authMode());
        if (!s.devTools() && (s.pnrSalt() == null || s.pnrSalt().length() < 16)) throw new IllegalStateException("PNR_SALT must be set to a random string of at least 16 characters.");

        MutableClock movable = s.devTools() ? new MutableClock(DevTools.DESIGN_MOMENT) : null;
        Clock clock = movable != null ? movable : Clock.systemUTC();

        Db db = new Db(ds);
        KeyValueStore kv = s.redisUrl() == null || s.redisUrl().isBlank() ? new InMemoryKeyValueStore(clock) : new RedisKeyValueStore(s.redisUrl(), 2000);
        TrainRepository trains = new TrainRepository(db);
        TripRepository tripRepo = new TripRepository(db);
        UserRepository userRepo = new UserRepository(db);
        SnapshotRepository snaps = new SnapshotRepository(db);

        PostRepository postRepo = new PostRepository(db);
        ModerationRepository modRepo = new ModerationRepository(db);
        PostService postService = new PostService(postRepo, tripRepo, trains, modRepo, kv, clock);

        CommentRepository commentRepo = new CommentRepository(db);
        ReactionRepository reactionRepo = new ReactionRepository(db);
        ThreadService threadService = new ThreadService(commentRepo, reactionRepo, postRepo, postService, modRepo, clock);

        ConversationRepository convRepo = new ConversationRepository(db);
        MessageRepository msgRepo = new MessageRepository(db);
        ChatService chatService = new ChatService(convRepo, msgRepo, postRepo, postService, modRepo, clock);

        ModerationService modService = new ModerationService(modRepo, postRepo, commentRepo, convRepo, userRepo, clock);

        ProviderAdapter provider;
        if ("railradar".equalsIgnoreCase(s.provider())) {
            provider = new RailRadarProviderAdapter(s.rapidApiKey());
        } else if ("rapidapi".equalsIgnoreCase(s.provider())) {
            provider = new RapidApiProviderAdapter(s.rapidApiKey());
        } else if ("mock".equalsIgnoreCase(s.provider())) {
            provider = new MockProviderAdapter(clock, trains, Map.of("12345:2026-10-25", 12));
        } else {
            throw new IllegalStateException("Unknown PROVIDER '" + s.provider() + "'. Allowed: 'railradar', 'rapidapi', 'mock'.");
        }

        Executor async = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "status-refresh");
            t.setDaemon(true);
            return t;
        });
        StatusService status = new StatusService(clock, kv, trains, tripRepo, snaps, provider,
            new BudgetGuard(kv, snaps, clock, s.dailyProviderBudget()), new CircuitBreaker(clock, 5, Duration.ofSeconds(60)),
            new StatusValidator(), async, StatusService.Config.defaults());

        TokenVerifier verifier = "dev".equals(s.authMode()) ? new DevTokenVerifier()
            : new FirebaseTokenVerifier(s.firebaseProjectId(), jwksOverride != null ? jwksOverride : new GoogleJwksSource(GoogleJwksSource.FIREBASE_JWKS_URL, clock), clock);
        DevTools dev = movable != null ? new DevTools(db, movable, kv, status, userRepo, tripRepo) : null;

        app.trainmate.auth.FirebaseAuthDeleter fbDeleter = new app.trainmate.auth.RestFirebaseAuthDeleter(System.getenv("FIREBASE_SERVICE_ACCOUNT_TOKEN"));
        UserService userService = new UserService(userRepo, fbDeleter);

        ApiHandler handler = new ApiHandler(new ApiHandler.Deps(
            clock, verifier, userService,
            new TripService(tripRepo, trains, clock, s.pnrSalt() == null ? "" : s.pnrSalt()),
            tripRepo, trains, status, postService, threadService, chatService, modService, dev
        ));

        return new App(handler, clock, kv);
    }
}
