package com.skatingcoach.conversation;

import com.skatingcoach.access.CurrentActorService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import static com.skatingcoach.conversation.ConversationModels.*;

@RestController
@RequestMapping("/api/v1/sessions")
public class ConversationController {
    private final CurrentActorService actors;
    private final ConversationService conversations;

    public ConversationController(CurrentActorService actors, ConversationService conversations) {
        this.actors = actors;
        this.conversations = conversations;
    }

    @PostMapping
    public SessionView create() { return conversations.create(actors.requireActor()); }

    @PostMapping("/{id}/messages")
    public MessageView append(@PathVariable UUID id, @Valid @RequestBody MessageInput input) {
        return conversations.append(actors.requireActor(), id, input);
    }
}
