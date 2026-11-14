package com.skatingcoach.planning;

import com.skatingcoach.access.CurrentActorService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;
import static com.skatingcoach.planning.PlanModels.*;

@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {
    private final CurrentActorService actors;
    private final PlanService plans;
    public PlanController(CurrentActorService actors, PlanService plans) { this.actors = actors; this.plans = plans; }
    @GetMapping public List<PlanView> list() { return plans.list(actors.requireActor()); }
    @GetMapping("/{id}") public PlanView get(@PathVariable UUID id) { return plans.get(actors.requireActor(), id); }
    @PostMapping public PlanView create(@Valid @RequestBody PlanInput input) { return plans.create(actors.requireActor(), input); }
    @PatchMapping("/{id}") public PlanView update(@PathVariable UUID id, @Valid @RequestBody PlanInput input) { return plans.update(actors.requireActor(), id, input); }
    @PostMapping("/{id}/recovery") public RecoveryView recovery(@PathVariable UUID id, @RequestBody RecoveryInput input) { return plans.recovery(actors.requireActor(), id, input); }
}
