package app.trainmate.thread;

import app.trainmate.post.PostDtos.PublicUserDto;

public final class CommentDtos {
    private CommentDtos() {}

    public record CommentDto(
        String id,
        String requestId,
        PublicUserDto author,
        String body,
        String createdAt,
        boolean isMine
    ) {}
}
