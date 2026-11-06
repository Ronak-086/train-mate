package app.trainmate.thread;

import app.trainmate.http.ApiException;
import app.trainmate.http.Body;
import app.trainmate.post.PostDtos.PublicUserDto;
import app.trainmate.post.PostRepository;
import app.trainmate.post.PostRepository.RequestRecord;
import app.trainmate.post.PostService;
import app.trainmate.safety.ModerationRepository;
import app.trainmate.thread.CommentDtos.CommentDto;
import app.trainmate.thread.CommentRepository.CommentRecord;
import app.trainmate.user.UserRepository.UserRow;
import app.trainmate.util.Ist;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ThreadService {
    private static final Set<String> REACTION_TYPES = Set.of("CONFIRMED", "HELPFUL", "OUTDATED");
    private static final Pattern LINK_RE = Pattern.compile("(?is).*(https?://|www\\.).*");

    private final CommentRepository comments;
    private final ReactionRepository reactions;
    private final PostRepository posts;
    private final PostService postService;
    private final ModerationRepository moderation;
    private final Clock clock;

    public ThreadService(CommentRepository comments, ReactionRepository reactions, PostRepository posts,
                         PostService postService, ModerationRepository moderation, Clock clock) {
        this.comments = comments;
        this.reactions = reactions;
        this.posts = posts;
        this.postService = postService;
        this.moderation = moderation;
        this.clock = clock;
    }

    public List<CommentDto> listComments(UserRow me, UUID requestId) {
        RequestRecord p = posts.byId(requestId);
        if (p == null || !postService.canSeeLive(me.id(), p)) {
            throw ApiException.notFound("Post not found.");
        }

        List<CommentRecord> records = comments.listForPost(requestId);
        List<CommentDto> out = new ArrayList<>();
        for (CommentRecord c : records) {
            if (moderation.isBlockedBetween(me.id(), c.userId())) continue;
            out.add(new CommentDto(
                String.valueOf(c.id()),
                requestId.toString(),
                new PublicUserDto(c.userId().toString(), c.authorDisplayName()),
                c.body(),
                Ist.iso(c.createdAt()),
                c.userId().equals(me.id())
            ));
        }
        return out;
    }

    public CommentDto addComment(UserRow me, UUID requestId, Body body) {
        RequestRecord p = posts.byId(requestId);
        if (p == null || !postService.canSeeLive(me.id(), p)) {
            throw ApiException.notFound("Post not found.");
        }

        Instant hourAgo = clock.instant().minus(Duration.ofHours(1));
        if (comments.countUserCommentsInLastHour(me.id(), hourAgo) >= 20) {
            throw ApiException.tooMany("COMMENT_RATE_LIMIT", "You've reached the comment limit for this hour.", 3600);
        }

        String text = body.str("body", 1, 500, true);
        if (postService.isNewAccount(me) && LINK_RE.matcher(text).matches()) {
            throw ApiException.forbidden("LINKS_NOT_ALLOWED", "New accounts can't post links.");
        }

        Instant now = clock.instant();
        long id = comments.insert(requestId, me.id(), text, now);
        return new CommentDto(
            String.valueOf(id),
            requestId.toString(),
            new PublicUserDto(me.id().toString(), me.displayName()),
            text,
            Ist.iso(now),
            true
        );
    }

    public void addReaction(UserRow me, UUID requestId, Body body) {
        RequestRecord p = posts.byId(requestId);
        if (p == null || !postService.canSeeLive(me.id(), p)) {
            throw ApiException.notFound("Post not found.");
        }

        String type = body.str("type", 1, 20, true).toUpperCase();
        if (!REACTION_TYPES.contains(type)) throw ApiException.bad("Invalid reaction type: " + type);

        reactions.add(requestId, me.id(), type, clock.instant());
        if ("OUTDATED".equals(type) && reactions.countOutdated(requestId) >= 3) {
            posts.updateStatus(requestId, "EXPIRED");
        }
    }

    public void removeReaction(UserRow me, UUID requestId, String type) {
        if (type == null) throw ApiException.bad("type is required");
        type = type.toUpperCase();
        if (!REACTION_TYPES.contains(type)) throw ApiException.bad("Invalid reaction type: " + type);
        reactions.remove(requestId, me.id(), type);
    }
}
