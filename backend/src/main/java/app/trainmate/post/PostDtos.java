package app.trainmate.post;

import java.util.List;
import java.util.Map;

public final class PostDtos {
    private PostDtos() {}

    public record PublicUserDto(String id, String displayName) {}

    public record PostDto(
        String id,
        String kind,
        String scope,
        String category,
        String title,
        String note,
        boolean isUrgent,
        String offeringBerth,
        String wantsBerth,
        int groupSize,
        String status,
        String trainNo,
        String journeyDate,
        String coach,
        String createdAt,
        String expiresAt,
        PublicUserDto author,
        boolean isMine,
        int replyCount,
        Map<String, Integer> reactions,
        List<String> myReactions,
        boolean highlighted,
        boolean dimmed,
        String myConversationId
    ) {}

    public record BoardPageDto(List<PostDto> items, String nextCursor) {}
}
