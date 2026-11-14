package com.skatingcoach.access;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class DevSessionRegistry {
    public static final UUID DEMO_ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    public static final UUID DEMO_ATHLETE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public DevSessionRegistry() { this(Clock.systemUTC()); }
    DevSessionRegistry(Clock clock) { this.clock = clock; }

    public Session create() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        Session session = new Session(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                new CurrentActor(DEMO_ACTOR_ID, DEMO_ATHLETE_ID), now.plus(Duration.ofHours(8)));
        sessions.put(session.token(), session);
        return session;
    }

    public Optional<CurrentActor> authenticate(String token) {
        Session session = sessions.get(token);
        if (session == null) return Optional.empty();
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session.actor());
    }

    public record Session(String token, CurrentActor actor, Instant expiresAt) {}
}
