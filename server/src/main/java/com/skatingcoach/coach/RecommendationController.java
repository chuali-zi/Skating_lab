package com.skatingcoach.coach;

import com.skatingcoach.access.CurrentActorService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import static com.skatingcoach.coach.RecommendationModels.*;

@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {
    private final CurrentActorService actors;
    private final RecommendationService recommendations;
    public RecommendationController(CurrentActorService actors, RecommendationService recommendations) {
        this.actors = actors;
        this.recommendations = recommendations;
    }
    @PostMapping public RunView submit(@Valid @RequestBody RecommendationInput input) { return recommendations.submit(actors.requireActor(), input); }
    @GetMapping("/{id}") public RunView get(@PathVariable UUID id) { return recommendations.get(actors.requireActor(), id); }
}
