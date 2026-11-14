package com.skatingcoach.access;

import com.skatingcoach.persistence.TenantJdbc;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CurrentActorController {
    private final CurrentActorService actors;
    private final TenantJdbc tenant;
    private final JdbcTemplate jdbc;
    public CurrentActorController(CurrentActorService actors, TenantJdbc tenant, JdbcTemplate jdbc) {
        this.actors = actors;
        this.tenant = tenant;
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/me")
    public ActorView current() {
        CurrentActor actor = actors.requireActor();
        return tenant.read(actor, () -> new ActorView(actor.actorId(), actor.athleteId(),
                jdbc.queryForObject("SELECT display_name FROM sc_actor WHERE id=?", String.class, actor.actorId())));
    }

    public record ActorView(UUID actorId, UUID athleteId, String displayName) {}
}
