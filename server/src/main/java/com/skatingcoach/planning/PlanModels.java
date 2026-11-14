package com.skatingcoach.planning;

import com.fasterxml.jackson.databind.JsonNode;
import com.skatingcoach.scoring.ScoringModels;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PlanModels {
    private PlanModels() {}
    public enum Kind { MAIN, RECOVERY }
    public record PlanInput(@NotBlank String title, @NotNull Kind kind, @NotNull JsonNode body, Long expectedRevision) {}
    public record PlanView(UUID id, String title, Kind kind, long revision, JsonNode body, Instant createdAt, Instant updatedAt,
                           boolean contextInvalidated) {}
    public record RecoveryInput(long expectedRevision, String title, List<ScoringModels.Element> completedPrefix,
                                List<ScoringModels.Element> proposedElements, ScoringModels.RuleSet ruleSet,
                                ScoringModels.SelectionPolicy selection, String failureDescription) {}
    public record RecoveryView(PlanView plan, ScoringModels.Evaluation evaluation) {}
}
