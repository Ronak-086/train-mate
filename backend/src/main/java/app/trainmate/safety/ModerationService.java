package app.trainmate.safety;

import app.trainmate.chat.ConversationRepository;
import app.trainmate.chat.ConversationRepository.ConversationRecord;
import app.trainmate.http.ApiException;
import app.trainmate.http.Body;
import app.trainmate.post.PostRepository;
import app.trainmate.post.PostRepository.RequestRecord;
import app.trainmate.thread.CommentRepository;
import app.trainmate.thread.CommentRepository.CommentRecord;
import app.trainmate.user.UserRepository;
import app.trainmate.user.UserRepository.UserRow;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public final class ModerationService {
    private final ModerationRepository moderation;
    private final PostRepository posts;
    private final CommentRepository comments;
    private final ConversationRepository convs;
    private final UserRepository users;
    private final Clock clock;

    public ModerationService(ModerationRepository moderation, PostRepository posts,
                             CommentRepository comments, ConversationRepository convs,
                             UserRepository users, Clock clock) {
        this.moderation = moderation;
        this.posts = posts;
        this.comments = comments;
        this.convs = convs;
        this.users = users;
        this.clock = clock;
    }

    public void report(UserRow me, Body body) {
        UUID targetUserId = body.uuid("targetUserId", true);
        String reason = body.str("reason", 1, 300, true);
        UUID requestId = body.uuid("requestId", false);
        Long commentId = body.integer("commentId", 1, Integer.MAX_VALUE, false) != null
            ? body.integer("commentId", 1, Integer.MAX_VALUE, false).longValue() : null;

        if (targetUserId.equals(me.id())) throw ApiException.bad("You can't report yourself.");
        if (users.byId(targetUserId) == null) throw ApiException.notFound("USER_NOT_FOUND", "That person doesn't exist.");

        RequestRecord post = requestId != null ? posts.byId(requestId) : null;
        CommentRecord comment = commentId != null ? comments.byId(commentId) : null;

        if (requestId != null && post == null) throw ApiException.notFound("That post doesn't exist.");
        if (commentId != null && comment == null) throw ApiException.notFound("That comment doesn't exist.");

        UUID author = comment != null ? comment.userId() : (post != null ? post.userId() : null);
        if (author != null && !author.equals(targetUserId)) {
            throw ApiException.bad("TARGET_MISMATCH", "targetUserId must be the author of the reported post or comment.");
        }

        Instant now = clock.instant();
        moderation.insertReport(me.id(), targetUserId, requestId, commentId, reason, now);

        if (commentId != null) {
            if (moderation.distinctReportersForComment(commentId) >= 3) {
                comments.hideComment(commentId);
            }
        } else if (requestId != null) {
            if (moderation.distinctReportersForRequest(requestId) >= 3) {
                posts.hidePost(requestId);
            }
        }
    }

    public void block(UserRow me, UUID targetId) {
        if (targetId.equals(me.id())) throw ApiException.bad("You can't block yourself.");
        if (users.byId(targetId) == null) throw ApiException.notFound("USER_NOT_FOUND", "That person doesn't exist.");
        moderation.block(me.id(), targetId, clock.instant());
    }

    public void unblock(UserRow me, UUID targetId) {
        moderation.unblock(me.id(), targetId);
    }

    public void review(UserRow me, Body body) {
        UUID convId = body.uuid("conversationId", true);
        Integer rating = body.integer("rating", 1, 5, true);

        ConversationRecord conv = convs.byId(convId);
        if (conv == null || (!conv.requesterId().equals(me.id()) && !conv.helperId().equals(me.id()))) {
            throw ApiException.notFound("Conversation not found.");
        }
        if (!"DONE".equals(conv.status())) {
            throw ApiException.conflict("NOT_DONE", "You can rate once the swap or help is marked done.");
        }
        if (moderation.hasReviewed(convId, me.id())) {
            throw ApiException.conflict("ALREADY_REVIEWED", "You've already rated this.");
        }

        UUID revieweeId = conv.requesterId().equals(me.id()) ? conv.helperId() : conv.requesterId();
        Instant now = clock.instant();
        moderation.insertReview(convId, me.id(), revieweeId, rating, now);

        int delta = rating >= 4 ? 2 : (rating <= 2 ? -3 : 0);
        if (delta != 0) {
            moderation.updateTrustScore(revieweeId, delta);
        }
    }
}
