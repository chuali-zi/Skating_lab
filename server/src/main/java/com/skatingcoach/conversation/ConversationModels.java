package com.skatingcoach.conversation;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

public final class ConversationModels {
    private ConversationModels() {}
    public record SessionView(UUID id, long revision, Instant createdAt) {}
    public record MessageInput(@NotBlank String requestId, @NotBlank String content) {}
    public record MessageView(UUID id, UUID sessionId, long sessionRevision, String content, Instant createdAt) {}
}
