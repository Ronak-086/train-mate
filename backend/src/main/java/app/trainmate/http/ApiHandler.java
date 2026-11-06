package app.trainmate.http;

import app.trainmate.auth.TokenVerifier;
import app.trainmate.chat.ChatService;
import app.trainmate.dev.DevTools;
import app.trainmate.post.PostDtos.BoardPageDto;
import app.trainmate.post.PostDtos.PostDto;
import app.trainmate.post.PostRepository.RequestRecord;
import app.trainmate.post.PostService;
import app.trainmate.safety.ModerationService;
import app.trainmate.thread.ThreadService;
import app.trainmate.train.Dtos.TrainStatusDto;
import app.trainmate.train.RouteStop;
import app.trainmate.train.StatusService;
import app.trainmate.train.TrainRepository;
import app.trainmate.trip.TripDtos.TripDto;
import app.trainmate.trip.TripRepository;
import app.trainmate.trip.TripService;
import app.trainmate.user.UserRepository.UserRow;
import app.trainmate.user.UserService;
import app.trainmate.util.Ist;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The full HTTP API as a function from {@link Req} to {@link Res}, independent of any web framework.
 * Handles authentication, authorization, validation, board filtering, chat, comments, reactions,
 * moderation, user lifecycle, and train status.
 */
public final class ApiHandler {
    private static final System.Logger LOG = System.getLogger(ApiHandler.class.getName());

    public record Deps(
        Clock clock,
        TokenVerifier verifier,
        UserService users,
        TripService trips,
        TripRepository tripRepo,
        TrainRepository trains,
        StatusService status,
        PostService posts,
        ThreadService threads,
        ChatService chat,
        ModerationService moderation,
        DevTools dev
    ) {}

    private enum Auth { NONE, OPTIONAL, REQUIRED }

    private record Ctx(Req req, Map<String, String> params, UserRow me) {
        Body body() { return Body.parse(req.body()); }
    }

    @FunctionalInterface
    private interface Handler { Res handle(Ctx c); }

    private record Route(String method, Pattern re, List<String> keys, Handler handler, Auth auth) {}

    private final Deps d;
    private final List<Route> routes = new ArrayList<>();

    public ApiHandler(Deps deps) {
        this.d = deps;
        add("GET", "/health", c -> Res.ok(Map.of("status", "ok", "now", Ist.iso(d.clock().instant()))), Auth.NONE);

        // Trains (public)
        add("GET", "/trains/search", this::searchTrains, Auth.NONE);
        add("GET", "/trains/:no/route", this::route, Auth.NONE);
        add("GET", "/trains/:no/status", this::status, Auth.OPTIONAL);

        // Trips (authenticated)
        add("GET", "/trips/mine", c -> Res.ok(Map.of("items", d.trips().mine(c.me().id()))), Auth.REQUIRED);
        add("POST", "/trips", c -> Res.json(201, d.trips().create(c.me().id(), c.body())), Auth.REQUIRED);
        add("DELETE", "/trips/:id", c -> {
            d.trips().delete(c.me().id(), c.params().get("id"));
            return Res.noContent();
        }, Auth.REQUIRED);

        // Feed & Board
        add("GET", "/feed/around-me", this::aroundYou, Auth.REQUIRED);
        add("GET", "/board", this::board, Auth.REQUIRED);

        // Posts
        add("POST", "/requests", this::createPost, Auth.REQUIRED);
        add("GET", "/requests/:id", this::getPost, Auth.REQUIRED);
        add("PATCH", "/requests/:id", this::patchPost, Auth.REQUIRED);
        add("DELETE", "/requests/:id", this::deletePost, Auth.REQUIRED);

        // Comments & Reactions
        add("GET", "/requests/:id/comments", c -> Res.ok(Map.of("items", d.threads().listComments(c.me(), parseUuid(c.params().get("id"))))), Auth.REQUIRED);
        add("POST", "/requests/:id/comments", c -> Res.json(201, d.threads().addComment(c.me(), parseUuid(c.params().get("id")), c.body())), Auth.REQUIRED);
        add("POST", "/requests/:id/reactions", c -> {
            d.threads().addReaction(c.me(), parseUuid(c.params().get("id")), c.body());
            return Res.noContent();
        }, Auth.REQUIRED);
        add("DELETE", "/requests/:id/reactions", c -> {
            d.threads().removeReaction(c.me(), parseUuid(c.params().get("id")), c.req().q("type"));
            return Res.noContent();
        }, Auth.REQUIRED);

        // Chat & Conversations
        add("POST", "/requests/:id/respond", c -> Res.json(201, d.chat().respond(c.me(), parseUuid(c.params().get("id")), c.body())), Auth.REQUIRED);
        add("GET", "/conversations/mine", c -> Res.ok(Map.of("items", d.chat().myConversations(c.me()))), Auth.REQUIRED);
        add("GET", "/conversations/:id", c -> Res.ok(d.chat().getConversation(c.me(), parseUuid(c.params().get("id")))), Auth.REQUIRED);
        add("PATCH", "/conversations/:id", c -> Res.ok(d.chat().patchConversation(c.me(), parseUuid(c.params().get("id")), c.body())), Auth.REQUIRED);
        add("GET", "/conversations/:id/messages", this::listMessages, Auth.REQUIRED);
        add("POST", "/conversations/:id/messages", c -> Res.json(201, d.chat().sendMessage(c.me(), parseUuid(c.params().get("id")), c.body())), Auth.REQUIRED);

        // Trust, Safety & Moderation
        add("POST", "/reports", c -> {
            d.moderation().report(c.me(), c.body());
            return Res.noContent();
        }, Auth.REQUIRED);
        add("POST", "/users/:id/block", c -> {
            d.moderation().block(c.me(), parseUuid(c.params().get("id")));
            return Res.noContent();
        }, Auth.REQUIRED);
        add("DELETE", "/users/:id/block", c -> {
            d.moderation().unblock(c.me(), parseUuid(c.params().get("id")));
            return Res.noContent();
        }, Auth.REQUIRED);
        add("POST", "/reviews", c -> {
            d.moderation().review(c.me(), c.body());
            return Res.noContent();
        }, Auth.REQUIRED);

        // User Account Management
        add("GET", "/me", c -> Res.ok(UserService.dto(c.me())), Auth.REQUIRED);
        add("PATCH", "/me", c -> Res.ok(d.users().rename(c.me(), c.body())), Auth.REQUIRED);
        add("DELETE", "/me", c -> {
            d.users().delete(c.me());
            return Res.noContent();
        }, Auth.REQUIRED);

        // Development Helpers
        if (d.dev() != null) {
            add("POST", "/dev/reset", c -> {
                d.dev().reset();
                return Res.noContent();
            }, Auth.NONE);
            add("POST", "/dev/clock", c -> Res.ok(Map.of("now", d.dev().setClock(c.body().raw("iso")))), Auth.NONE);
        }
    }

    private void add(String method, String path, Handler h, Auth auth) {
        List<String> keys = new ArrayList<>();
        Matcher m = Pattern.compile(":([a-z]+)").matcher(path);
        StringBuilder re = new StringBuilder("^");
        int last = 0;
        while (m.find()) {
            re.append(Pattern.quote(path.substring(last, m.start()))).append("([^/]+)");
            keys.add(m.group(1));
            last = m.end();
        }
        re.append(Pattern.quote(path.substring(last))).append("$");
        routes.add(new Route(method, Pattern.compile(re.toString()), keys, h, auth));
    }

    // ---- request dispatching ----------------------------------------------------------------------

    public Res handle(Req req) {
        try {
            String path = req.path().replaceFirst("^/api(?=/|$)", "");
            path = path.replaceAll("/+$", "");
            if (path.isEmpty()) path = "/";
            boolean pathMatched = false;
            for (Route r : routes) {
                Matcher m = r.re().matcher(path);
                if (!m.matches()) continue;
                pathMatched = true;
                if (!r.method().equals(req.method())) continue;

                UserRow me = authenticate(req, r.auth());
                Map<String, String> params = new LinkedHashMap<>();
                for (int i = 0; i < r.keys().size(); i++) params.put(r.keys().get(i), URLDecoder.decode(m.group(i + 1), StandardCharsets.UTF_8));
                return r.handler().handle(new Ctx(req, params, me));
            }
            if (pathMatched) throw new ApiException(405, "METHOD_NOT_ALLOWED", "That method isn't supported here.");
            throw ApiException.notFound("ENDPOINT_NOT_FOUND", "There's no such endpoint.");
        } catch (ApiException e) {
            return Res.problem(e);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Unexpected error handling " + req.method() + " " + req.path(), e);
            return Res.problem(new ApiException(500, "INTERNAL_ERROR", "Something went wrong on our side."));
        }
    }

    private UserRow authenticate(Req req, Auth mode) {
        if (mode == Auth.NONE) return null;
        String header = req.header("authorization");
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : null;
        if (token == null || token.isEmpty()) {
            if (mode == Auth.OPTIONAL && header == null) return null;
            throw ApiException.unauthenticated("Sign in to continue.");
        }
        return d.users().resolve(d.verifier().verify(token));
    }

    // ---- handlers ---------------------------------------------------------------------------------

    private Res searchTrains(Ctx c) {
        String q = c.req().q("q") == null ? "" : c.req().q("q").trim();
        if (q.length() < 2) throw ApiException.bad("Type at least 2 characters to search.");
        return Res.ok(Map.of("items", d.trains().search(q).stream().map(TrainRepository.TrainInfo::dto).toList()));
    }

    private Res route(Ctx c) {
        List<RouteStop> stops = d.trains().route(c.params().get("no"));
        if (stops.isEmpty()) throw ApiException.notFound("TRAIN_NOT_FOUND", "We don't have that train.");
        List<Object> out = new ArrayList<>();
        for (RouteStop s : stops) {
            out.add(new app.trainmate.train.Dtos.RouteStopDto(s.seq(), s.station(), s.arr() == null ? null : s.arr().toString(),
                s.dep() == null ? null : s.dep().toString(), s.dayOffset()));
        }
        return Res.ok(Map.of("trainNo", c.params().get("no"), "stops", out));
    }

    private Res status(Ctx c) {
        String no = c.params().get("no");
        if (d.trains().find(no) == null) throw ApiException.notFound("TRAIN_NOT_FOUND", "We don't have that train.");
        LocalDate date = Body.date(c.req().q("date"), "date");
        boolean tracked = c.me() != null && d.tripRepo().exists(c.me().id(), no, date);
        TrainStatusDto dto = d.status().get(no, date, tracked);
        return Res.ok(dto);
    }

    private Res aroundYou(Ctx c) {
        LocalDate today = Ist.date(d.clock().instant());
        TripDto trip = d.tripRepo().active(c.me().id(), today);
        if (trip == null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("trip", null);
            out.put("items", List.of());
            return Res.ok(out);
        }

        LocalDate journeyDate = LocalDate.parse(trip.journeyDate());
        // Board items for this active train
        BoardPageDto page = d.posts().board(c.me(), trip.trainNo(), journeyDate, null, null, null, 20, null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trip", trip);
        out.put("items", page.items());
        return Res.ok(out);
    }

    private Res board(Ctx c) {
        String train = c.req().q("train");
        String dateStr = c.req().q("date");
        LocalDate date = dateStr != null && !dateStr.isBlank() ? Body.date(dateStr, "date") : null;
        String kind = c.req().q("kind");
        String scope = c.req().q("scope");
        String coach = c.req().q("coach");
        int limit = 20;
        try {
            if (c.req().q("limit") != null) limit = Integer.parseInt(c.req().q("limit"));
        } catch (NumberFormatException ignored) {}
        String cursor = c.req().q("cursor");

        BoardPageDto page = d.posts().board(c.me(), train, date, kind, scope, coach, limit, cursor);
        return Res.ok(page);
    }

    private Res createPost(Ctx c) {
        String idem = c.req().header("idempotency-key");
        PostDto post = d.posts().create(c.me(), c.body(), idem);
        return Res.json(201, post);
    }

    private Res getPost(Ctx c) {
        UUID id = parseUuid(c.params().get("id"));
        PostDto post = d.posts().get(c.me(), id);
        return Res.ok(post);
    }

    private Res patchPost(Ctx c) {
        UUID id = parseUuid(c.params().get("id"));
        PostDto post = d.posts().patch(c.me(), id, c.body());
        return Res.ok(post);
    }

    private Res deletePost(Ctx c) {
        UUID id = parseUuid(c.params().get("id"));
        d.posts().delete(c.me(), id);
        return Res.noContent();
    }

    private Res listMessages(Ctx c) {
        UUID convId = parseUuid(c.params().get("id"));
        long after = 0;
        try {
            if (c.req().q("after") != null) after = Long.parseLong(c.req().q("after"));
        } catch (NumberFormatException ignored) {}
        return Res.ok(Map.of("items", d.chat().listMessages(c.me(), convId, after)));
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (Exception e) {
            throw ApiException.notFound("Invalid identifier.");
        }
    }
}
