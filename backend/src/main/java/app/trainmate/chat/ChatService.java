package app.trainmate.chat;

import app.trainmate.chat.ChatDtos.ConversationDto;
import app.trainmate.chat.ChatDtos.LastMessageDto;
import app.trainmate.chat.ChatDtos.MessageDto;
import app.trainmate.chat.ConversationRepository.ConversationRecord;
import app.trainmate.chat.MessageRepository.MessageRecord;
import app.trainmate.http.ApiException;
import app.trainmate.http.Body;
import app.trainmate.post.PostDtos.PublicUserDto;
import app.trainmate.post.PostRepository;
import app.trainmate.post.PostRepository.RequestRecord;
import app.trainmate.post.PostService;
import app.trainmate.safety.ModerationRepository;
import app.trainmate.user.UserRepository.UserRow;
import app.trainmate.util.Ist;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ChatService {
    private static final Set<String> PRIVATE_KINDS = Set.of("SWAP", "HELP", "SHARE");
    private static final Pattern LINK_RE = Pattern.compile("(?is).*(https?://|www\\.).*");

    private final ConversationRepository convs;
    private final MessageRepository messages;
    private final PostRepository posts;
    private final PostService postService;
    private final ModerationRepository moderation;
    private final Clock clock;

    public ChatService(ConversationRepository convs, MessageRepository messages, PostRepository posts,
                       PostService postService, ModerationRepository moderation, Clock clock) {
        this.convs = convs;
        this.messages = messages;
        this.posts = posts;
        this.postService = postService;
        this.moderation = moderation;
        this.clock = clock;
    }

    public ConversationDto respond(UserRow me, UUID postId, Body body) {
        RequestRecord post = posts.byId(postId);
        if (post == null || post.userId().equals(me.id()) || !postService.inScope(me.id(), post)) {
            throw ApiException.notFound();
        }
        if (!PRIVATE_KINDS.contains(post.kind())) {
            throw ApiException.bad("RESPOND_NOT_ALLOWED", "This kind of post uses public comments, not private offers.");
        }
        if (!postService.canSeeLive(me.id(), post)) {
            throw new ApiException(410, "POST_UNAVAILABLE", "This post is no longer available.");
        }
        if (moderation.isBlockedBetween(me.id(), post.userId())) {
            throw ApiException.notFound();
        }

        ConversationRecord existing = convs.findExisting(postId, me.id());
        if (existing != null) {
            throw new ApiException(409, "CONVERSATION_EXISTS", "You've already responded to this post.",
                Map.of("conversationId", existing.id().toString()));
        }

        String first = body.str("message", 0, 200, false);
        if (first != null && postService.isNewAccount(me) && LINK_RE.matcher(first).matches()) {
            throw ApiException.forbidden("LINKS_NOT_ALLOWED", "New accounts can't post links yet.");
        }

        Instant now = clock.instant();
        UUID id = convs.insert(postId, post.userId(), me.id(), now);
        if (first != null && !first.isBlank()) {
            messages.insert(id, me.id(), first, now);
        }

        return toDto(convs.byId(id), me.id());
    }

    public List<ConversationDto> myConversations(UserRow me) {
        List<ConversationRecord> list = convs.listForUser(me.id());
        List<ConversationDto> out = new ArrayList<>();
        for (ConversationRecord c : list) {
            out.add(toDto(c, me.id()));
        }
        out.sort((a, b) -> {
            String timeA = a.lastMessage() != null ? a.lastMessage().createdAt() : a.createdAt();
            String timeB = b.lastMessage() != null ? b.lastMessage().createdAt() : b.createdAt();
            return timeB.compareTo(timeA);
        });
        return out;
    }

    public ConversationDto getConversation(UserRow me, UUID convId) {
        ConversationRecord c = loadConv(me, convId);
        return toDto(c, me.id());
    }

    public ConversationDto patchConversation(UserRow me, UUID convId, Body body) {
        ConversationRecord c = loadConv(me, convId);
        String action = body.str("status", 1, 20, true).toUpperCase();

        if ("ACCEPTED".equals(action) || "DECLINED".equals(action)) {
            if (!c.requesterId().equals(me.id())) {
                throw ApiException.forbidden("NOT_REQUESTER", "Only the person who posted can accept or decline.");
            }
            if (!"PENDING".equals(c.status())) {
                throw ApiException.conflict("INVALID_TRANSITION", "This offer is already " + c.status().toLowerCase() + ".");
            }
            convs.updateStatus(convId, action);
        } else if ("DONE".equals(action)) {
            if (!"ACCEPTED".equals(c.status()) && !"DONE".equals(c.status())) {
                throw ApiException.conflict("INVALID_TRANSITION", "Accept the offer before marking it done.");
            }

            boolean reqDone = c.requesterDone();
            boolean helpDone = c.helperDone();
            if (c.requesterId().equals(me.id())) reqDone = true;
            if (c.helperId().equals(me.id())) helpDone = true;

            String newStatus = (reqDone && helpDone) ? "DONE" : c.status();
            convs.markDone(convId, reqDone, helpDone, newStatus);

            if (reqDone && helpDone) {
                RequestRecord post = posts.byId(c.requestId());
                if (post != null && "OPEN".equals(post.status())) {
                    posts.updateStatus(post.id(), "MATCHED");
                }
            }
        } else {
            throw ApiException.bad("Invalid status transition: " + action);
        }

        return toDto(convs.byId(convId), me.id());
    }

    public List<MessageDto> listMessages(UserRow me, UUID convId, long afterId) {
        ConversationRecord c = loadConv(me, convId);
        List<MessageRecord> recs = messages.listAfter(c.id(), afterId, 100);
        List<MessageDto> out = new ArrayList<>();
        for (MessageRecord m : recs) {
            out.add(new MessageDto(
                m.id(),
                m.conversationId().toString(),
                m.senderId().toString(),
                m.body(),
                Ist.iso(m.createdAt()),
                m.senderId().equals(me.id())
            ));
        }
        return out;
    }

    public MessageDto sendMessage(UserRow me, UUID convId, Body body) {
        ConversationRecord c = loadConv(me, convId);
        UUID otherId = c.requesterId().equals(me.id()) ? c.helperId() : c.requesterId();
        if (moderation.isBlockedBetween(me.id(), otherId)) {
            throw ApiException.forbidden("BLOCKED", "You can't message this person.");
        }
        if ("PENDING".equals(c.status()) || "DECLINED".equals(c.status())) {
            throw ApiException.conflict("CHAT_NOT_ACCEPTED", "Chat opens once the offer is accepted.");
        }
        if ("DONE".equals(c.status())) {
            throw ApiException.conflict("CHAT_CLOSED", "This chat is closed because the swap is done.");
        }

        String text = body.str("body", 1, 1000, true);
        if (postService.isNewAccount(me) && LINK_RE.matcher(text).matches()) {
            throw ApiException.forbidden("LINKS_NOT_ALLOWED", "New accounts can't post links yet.");
        }

        Instant now = clock.instant();
        long id = messages.insert(convId, me.id(), text, now);
        return new MessageDto(
            id,
            convId.toString(),
            me.id().toString(),
            text,
            Ist.iso(now),
            true
        );
    }

    private ConversationRecord loadConv(UserRow me, UUID convId) {
        ConversationRecord c = convs.byId(convId);
        if (c == null || (!c.requesterId().equals(me.id()) && !c.helperId().equals(me.id()))) {
            throw ApiException.notFound("CONVERSATION_NOT_FOUND", "Conversation not found.");
        }
        return c;
    }

    private ConversationDto toDto(ConversationRecord c, UUID viewerId) {
        boolean iAmRequester = c.requesterId().equals(viewerId);
        MessageRecord lm = messages.lastMessage(c.id());
        LastMessageDto lastDto = lm == null ? null
            : new LastMessageDto(lm.body(), Ist.iso(lm.createdAt()), lm.senderId().toString());

        boolean myDone = iAmRequester ? c.requesterDone() : c.helperDone();
        boolean otherDone = iAmRequester ? c.helperDone() : c.requesterDone();

        return new ConversationDto(
            c.id().toString(),
            c.requestId().toString(),
            c.requestTitle(),
            c.requestKind(),
            c.requestCoach(),
            new PublicUserDto(c.requesterId().toString(), c.requesterName()),
            new PublicUserDto(c.helperId().toString(), c.helperName()),
            iAmRequester,
            c.status(),
            myDone,
            otherDone,
            Ist.iso(c.createdAt()),
            lastDto
        );
    }
}
