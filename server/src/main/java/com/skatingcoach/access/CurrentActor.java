package com.skatingcoach.access;

import java.util.Objects;
import java.util.UUID;

public record CurrentActor(UUID actorId, UUID athleteId) {
    public CurrentActor {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(athleteId, "athleteId");
    }
}
