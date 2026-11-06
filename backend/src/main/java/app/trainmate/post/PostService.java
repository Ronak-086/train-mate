package app.trainmate.post;

import app.trainmate.http.ApiException;
import app.trainmate.http.Body;
import app.trainmate.post.PostDtos.BoardPageDto;
import app.trainmate.post.PostDtos.PostDto;
import app.trainmate.post.PostDtos.PublicUserDto;
import app.trainmate.post.PostRepository.RequestRecord;
import app.trainmate.safety.ModerationRepository;
import app.trainmate.store.KeyValueStore;
import app.trainmate.train.RouteStop;
import app.trainmate.train.TrainRepository;
import app.trainmate.trip.TripDtos.TripDto;
import app.trainmate.trip.TripRepository;
import app.trainmate.user.UserRepository.UserRow;
import app.trainmate.util.Ist;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PostService {
    public static final Set<String> KINDS = Set.of("SWAP", "HELP", "ASK", "SHARE", "ALERT");
    public static final Set<String> SCOPES = Set.of("COACH", "NEARBY", "TRAIN");
    public static final Set<String> BERTHS = Set.of("LOWER", "MIDDLE", "UPPER", "SIDE_LOWER", "SIDE_UPPER", "WINDOW");
    public static final Set<String> CATEGORIES = Set.of("LUGGAGE", "MEDICAL", "FOOD", "TRANSLATION", "CHARGER", "OTHER");

    public static final Map<String, Set<String>> ALLOWED_SCOPES = Map.of(
        "SWAP", Set.of("COACH", "NEARBY"),
        "HELP", Set.of("COACH", "NEARBY"),
        "SHARE", Set.of("COACH"),
        "ASK", Set.of("COACH", "TRAIN"),
        "ALERT", Set.of("COACH", "TRAIN")
    );

    private static final Map<String, String> BERTH_LABELS = Map.of(
        "LOWER", "Lower berth", "MIDDLE", "Middle berth", "UPPER", "Upper berth",
        "SIDE_LOWER", "Side lower berth", "SIDE_UPPER", "Side upper berth", "WINDOW", "Window seat"
    );

    private static final Pattern COACH_RE = Pattern.compile("^([A-Z]{1,2})(\\d{1,2})$");
    private static final Pattern LINK_RE = Pattern.compile("(?is).*(https?://|www\\.).*");

    private final PostRepository posts;
    private final TripRepository trips;
    private final TrainRepository trains;
    private final ModerationRepository moderation;
    private final KeyValueStore kv;
    private final Clock clock;

    public PostService(PostRepository posts, TripRepository trips, TrainRepository trains,
                       ModerationRepository moderation, KeyValueStore kv, Clock clock) {
        this.posts = posts;
        this.trips = trips;
        this.trains = trains;
        this.moderation = moderation;
        this.kv = kv;
        this.clock = clock;
    }

    public static boolean coachesAdjacent(String a, String b) {
        if (a == null || b == null) return false;
        Matcher ma = COACH_RE.matcher(a);
        Matcher mb = COACH_RE.matcher(b);
        if (!ma.matches() || !mb.matches()) return false;
        if (!ma.group(1).equals(mb.group(1))) return false;
        return Math.abs(Integer.parseInt(ma.group(2)) - Integer.parseInt(mb.group(2))) <= 1;
    }

    public boolean isNewAccount(UserRow u) {
        Instant dayAgo = clock.instant().minus(Duration.ofHours(24));
        return u.createdAt().isAfter(dayAgo) || u.trustScore() < 0;
    }

    public boolean isJourneyActive(String trainNo, LocalDate date) {
        Instant start = Ist.startOfDay(date);
        List<RouteStop> stops = trains.route(trainNo);
        int lastOffset = stops.isEmpty() ? 0 : stops.get(stops.size() - 1).dayOffset();
        Instant end = Ist.startOfDay(date.plusDays(lastOffset + 1));
        Instant now = clock.instant();
        return !now.isBefore(start) && now.isBefore(end);
    }

    public PostDto create(UserRow me, Body body, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank() && kv != null) {
            String hit = kv.get("idem:post:" + me.id() + ":" + idempotencyKey).orElse(null);
            if (hit != null) {
                RequestRecord rec = posts.byId(UUID.fromString(hit));
                if (rec != null) return toDto(rec, me.id());
            }
        }

        UUID tripId = body.uuid("tripId", true);
        TripDto trip = trips.byId(tripId);
        if (trip == null || !trip.id().equals(tripId.toString())) {
            throw ApiException.notFound("TRIP_NOT_FOUND", "Trip not found.");
        }
        if (!trips.exists(me.id(), trip.trainNo(), LocalDate.parse(trip.journeyDate()))) {
            throw ApiException.notFound("TRIP_NOT_FOUND", "Trip not found.");
        }

        LocalDate journeyDate = LocalDate.parse(trip.journeyDate());
        if (!isJourneyActive(trip.trainNo(), journeyDate)) {
            throw ApiException.conflict("JOURNEY_NOT_ACTIVE", "You can post once your journey day has started, and until it ends.");
        }

        String kind = body.str("kind", 1, 10, true).toUpperCase();
        if (!KINDS.contains(kind)) throw ApiException.bad("Invalid post kind: " + kind);

        String scope = body.str("scope", 1, 10, false);
        scope = scope == null ? "COACH" : scope.toUpperCase();
        if (!SCOPES.contains(scope)) throw ApiException.bad("Invalid post scope: " + scope);

        Set<String> allowed = ALLOWED_SCOPES.get(kind);
        if (allowed == null || !allowed.contains(scope)) {
            throw ApiException.bad("SCOPE_NOT_ALLOWED", kind + " posts can't use the " + scope + " scope. Allowed: " + String.join(", ", allowed));
        }

        if (isNewAccount(me) && !"COACH".equals(scope)) {
            throw ApiException.forbidden("NEW_ACCOUNT_SCOPE", "New accounts can post to their own coach only.");
        }

        // Rate limits
        Instant now = clock.instant();
        Instant last = posts.lastPostTime(me.id());
        if (last != null) {
            long diffSec = Duration.between(last, now).getSeconds();
            if (diffSec < 120) {
                throw ApiException.tooMany("POST_COOLDOWN", "Please wait a moment before posting again.", 120 - (int) diffSec);
            }
        }

        Instant dayStart = Ist.startOfDay(Ist.date(now));
        if (posts.countUserPostsToday(me.id(), dayStart) >= 10) {
            long untilTomorrow = Duration.between(now, dayStart.plus(Duration.ofDays(1))).getSeconds();
            throw ApiException.tooMany("DAILY_LIMIT", "You can post 10 times a day. Try again tomorrow.", (int) untilTomorrow);
        }

        boolean isBroadcast = "ALERT".equals(kind) || "TRAIN".equals(scope);
        if (isBroadcast && posts.countBroadcastsForJourney(me.id(), tripId) >= 3) {
            throw ApiException.tooMany("BROADCAST_LIMIT", "You can send 3 alerts or train-wide posts per journey.", 3600);
        }

        String note = body.str("note", 0, 500, false);
        if (note != null && LINK_RE.matcher(note).matches() && isNewAccount(me)) {
            throw ApiException.forbidden("LINKS_NOT_ALLOWED", "New accounts can't post links.");
        }

        String offeringBerth = body.str("offeringBerth", 1, 20, false);
        if (offeringBerth != null && !BERTHS.contains(offeringBerth)) throw ApiException.bad("Invalid offeringBerth");
        String wantsBerth = body.str("wantsBerth", 1, 20, false);
        if (wantsBerth != null && !BERTHS.contains(wantsBerth)) throw ApiException.bad("Invalid wantsBerth");

        String title = body.str("title", 1, 80, false);
        if ("SWAP".equals(kind)) {
            if (offeringBerth == null) offeringBerth = trip.berthType() != null ? trip.berthType() : "UPPER";
            if (wantsBerth == null) wantsBerth = "LOWER";
            if (title == null || title.isBlank()) {
                title = BERTH_LABELS.getOrDefault(offeringBerth, offeringBerth) + " for " + BERTH_LABELS.getOrDefault(wantsBerth, wantsBerth).toLowerCase();
            }
        } else {
            if (title == null || title.isBlank()) throw ApiException.bad("title is required.");
        }

        String category = body.str("category", 1, 20, false);
        if (category != null && !CATEGORIES.contains(category)) throw ApiException.bad("Invalid category");
        boolean isUrgent = body.bool("isUrgent", false);
        Integer groupSize = body.integer("groupSize", 1, 10, false);
        if (groupSize == null) groupSize = 1;

        Integer expiresInHours = body.integer("expiresInHours", 1, 24, false);
        if (expiresInHours == null) expiresInHours = 24;

        List<RouteStop> stops = trains.route(trip.trainNo());
        int lastOffset = stops.isEmpty() ? 0 : stops.get(stops.size() - 1).dayOffset();
        Instant journeyEnd = Ist.startOfDay(journeyDate.plusDays(lastOffset + 1));
        Instant defaultExpiry = now.plus(Duration.ofHours(expiresInHours));
        Instant expiresAt = defaultExpiry.isBefore(journeyEnd) ? defaultExpiry : journeyEnd;

        UUID id = posts.insert(tripId, me.id(), trip.trainNo(), journeyDate, trip.coach(),
            kind, scope, category, title, note, isUrgent, offeringBerth, wantsBerth, groupSize, now, expiresAt);

        if (idempotencyKey != null && !idempotencyKey.isBlank() && kv != null) {
            kv.set("idem:post:" + me.id() + ":" + idempotencyKey, id.toString(), 86400);
        }

        return toDto(posts.byId(id), me.id());
    }

    public PostDto get(UserRow me, UUID postId) {
        RequestRecord p = posts.byId(postId);
        if (p == null) throw ApiException.notFound("That post doesn't exist.");
        if (moderation.isBlockedBetween(me.id(), p.userId())) {
            throw ApiException.notFound("That post doesn't exist.");
        }

        boolean live = isLive(p);
        boolean isMine = p.userId().equals(me.id());
        String myConv = posts.userConversationId(p.id(), me.id());
        if (!live && !isMine && myConv == null) {
            throw new ApiException(410, "POST_UNAVAILABLE", "This post is no longer available.");
        }
        return toDto(p, me.id());
    }

    public PostDto patch(UserRow me, UUID postId, Body body) {
        RequestRecord p = posts.byId(postId);
        if (p == null || !p.userId().equals(me.id())) throw ApiException.notFound("Post not found.");

        String title = body.str("title", 1, 80, false);
        String note = body.str("note", 0, 500, false);
        String status = body.str("status", 1, 20, false);
        if (status != null && !"CLOSED".equals(status)) {
            throw ApiException.bad("You can only change status to CLOSED.");
        }

        posts.patch(postId, me.id(), title, note, status);
        return toDto(posts.byId(postId), me.id());
    }

    public void delete(UserRow me, UUID postId) {
        RequestRecord p = posts.byId(postId);
        if (p == null || !p.userId().equals(me.id())) throw ApiException.notFound("Post not found.");
        posts.delete(postId, me.id());
    }

    public BoardPageDto board(UserRow me, String trainNo, LocalDate date, String kind, String scope, String coach, int limit, String cursor) {
        if (trainNo == null || date == null) {
            TripDto active = trips.active(me.id(), Ist.date(clock.instant()));
            if (active == null) throw ApiException.forbidden("TRIP_REQUIRED", "Add your train first to see its board.");
            trainNo = active.trainNo();
            date = LocalDate.parse(active.journeyDate());
        }

        if (!trips.exists(me.id(), trainNo, date)) {
            throw ApiException.forbidden("NO_TRIP_ON_TRAIN", "You can only see boards for trains you're travelling on.");
        }

        limit = Math.min(Math.max(limit, 1), 50);
        DecodedCursor dec = decodeCursor(cursor);

        List<RequestRecord> all = posts.listForTrain(trainNo, date);
        List<RequestRecord> visible = new ArrayList<>();
        for (RequestRecord p : all) {
            if (!canSeeLive(me.id(), p)) continue;
            if (kind != null && !kind.equalsIgnoreCase(p.kind())) continue;
            if (scope != null && !scope.equalsIgnoreCase(p.scope())) continue;
            if (coach != null && !coach.equalsIgnoreCase(p.coach())) continue;
            visible.add(p);
        }

        int start = 0;
        if (dec != null) {
            start = -1;
            for (int i = 0; i < visible.size(); i++) {
                RequestRecord p = visible.get(i);
                if (p.createdAt().toEpochMilli() < dec.t || (p.createdAt().toEpochMilli() == dec.t && p.id().toString().compareTo(dec.id) < 0)) {
                    start = i;
                    break;
                }
            }
            if (start < 0) start = visible.size();
        }

        List<PostDto> page = new ArrayList<>();
        for (int i = start; i < visible.size() && page.size() < limit; i++) {
            page.add(toDto(visible.get(i), me.id()));
        }

        String nextCursor = null;
        if (start + limit < visible.size()) {
            RequestRecord next = visible.get(start + limit - 1);
            nextCursor = encodeCursor(next.createdAt().toEpochMilli(), next.id().toString());
        }

        return new BoardPageDto(page, nextCursor);
    }

    public boolean canSeeLive(UUID viewerId, RequestRecord p) {
        if (p == null || !isLive(p)) return false;
        if (p.userId().equals(viewerId)) return true;
        if (moderation.isBlockedBetween(viewerId, p.userId())) return false;
        return inScope(viewerId, p);
    }

    public boolean inScope(UUID viewerId, RequestRecord p) {
        if (p == null) return false;
        List<TripDto> myTrips = trips.forUser(viewerId);
        TripDto trip = null;
        for (TripDto t : myTrips) {
            if (t.trainNo().equals(p.trainNo()) && t.journeyDate().equals(p.journeyDate().toString())) {
                trip = t;
                break;
            }
        }
        if (trip == null) return false;
        if ("TRAIN".equals(p.scope())) return true;
        if ("COACH".equals(p.scope())) return trip.coach().equalsIgnoreCase(p.coach());
        return coachesAdjacent(trip.coach(), p.coach());
    }

    public boolean isLive(RequestRecord p) {
        if (p == null || p.isHidden()) return false;
        if (!"OPEN".equals(p.status())) return false;
        return p.expiresAt().isAfter(clock.instant());
    }

    public String effectiveStatus(RequestRecord p) {
        if ("OPEN".equals(p.status()) && !p.expiresAt().isAfter(clock.instant())) return "EXPIRED";
        return p.status();
    }

    public PostDto toDto(RequestRecord p, UUID viewerId) {
        Map<String, Integer> counts = posts.reactionCounts(p.id());
        List<String> myReactions = posts.userReactions(p.id(), viewerId);
        String myConv = posts.userConversationId(p.id(), viewerId);
        int replies = posts.replyCount(p.id(), p.kind());

        int confirmed = counts.getOrDefault("CONFIRMED", 0);
        int outdated = counts.getOrDefault("OUTDATED", 0);

        return new PostDto(
            p.id().toString(),
            p.kind(),
            p.scope(),
            p.category(),
            p.title(),
            p.note(),
            p.isUrgent(),
            p.offeringBerth(),
            p.wantsBerth(),
            p.groupSize(),
            effectiveStatus(p),
            p.trainNo(),
            p.journeyDate().toString(),
            p.coach(),
            Ist.iso(p.createdAt()),
            Ist.iso(p.expiresAt()),
            new PublicUserDto(p.userId().toString(), p.authorDisplayName()),
            p.userId().equals(viewerId),
            replies,
            counts,
            myReactions,
            confirmed >= 3,
            outdated >= 3,
            myConv
        );
    }

    public record DecodedCursor(long t, String id) {}

    public static String encodeCursor(long t, String id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((t + ":" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static DecodedCursor decodeCursor(String c) {
        if (c == null || c.isBlank()) return null;
        try {
            String s = new String(Base64.getUrlDecoder().decode(c), StandardCharsets.UTF_8);
            int idx = s.indexOf(':');
            if (idx <= 0) return null;
            long t = Long.parseLong(s.substring(0, idx));
            String id = s.substring(idx + 1);
            return new DecodedCursor(t, id);
        } catch (Exception e) {
            return null;
        }
    }
}
