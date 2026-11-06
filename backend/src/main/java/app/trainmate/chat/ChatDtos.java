package app.trainmate.chat;

import app.trainmate.post.PostDtos.PublicUserDto;

public final class ChatDtos {
    private ChatDtos() {}

    public record LastMessageDto(String body, String createdAt, String senderId) {}

    public record ConversationDto(
        String id,
        String requestId,
        String requestTitle,
        String kind,
        String coach,
        PublicUserDto requester,
        PublicUserDto helper,
        boolean iAmRequester,
        String status,
        boolean myDone,
        boolean otherDone,
        String createdAt,
        LastMessageDto lastMessage
    ) {}

    public record MessageDto(
        long id,
        String conversationId,
        String senderId,
        String body,
        String createdAt,
        boolean isMine
    ) {}
}
