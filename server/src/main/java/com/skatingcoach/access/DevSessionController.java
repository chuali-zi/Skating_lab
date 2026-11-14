package com.skatingcoach.access;

import com.skatingcoach.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DevSessionController {
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    private final DevSessionRegistry sessions;
    private final boolean enabled;

    public DevSessionController(DevSessionRegistry sessions, Environment environment,
            @Value("${skating.dev-login-enabled:false}") boolean enabled) {
        this.sessions = sessions;
        this.enabled = enabled && environment.acceptsProfiles(Profiles.of("dev"));
    }

    @PostMapping("/api/v1/dev/session")
    public SessionView create(HttpServletRequest request) {
        if (!enabled || !LOOPBACK.contains(request.getRemoteAddr())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "该入口不可用。");
        }
        var session = sessions.create();
        return new SessionView(session.token(), session.actor().actorId(), session.actor().athleteId(),
                "开发体验选手", session.expiresAt(), true);
    }

    public record SessionView(String token, UUID actorId, UUID athleteId, String displayName,
                              Instant expiresAt, boolean devOnly) {}
}
