package com.skatingcoach.coach;

import com.fasterxml.jackson.databind.JsonNode;
import com.skatingcoach.scoring.ScoringModels;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.skatingcoach.video.VideoJobs.ReviewRef;

public final class RecommendationModels {
    private RecommendationModels() {}
    public record RecommendationInput(@NotBlank String requestId, @NotNull UUID sessionId, String prompt,
            UUID planId, String ruleBundleId, List<ScoringModels.Element> completedPrefix,
            ScoringModels.SelectionPolicy selection, String competitionKey, ReviewRef videoReview) {
        public RecommendationInput(String requestId,UUID sessionId,String prompt,UUID planId,String ruleBundleId,
                List<ScoringModels.Element> completedPrefix,ScoringModels.SelectionPolicy selection,String competitionKey) {
            this(requestId,sessionId,prompt,planId,ruleBundleId,completedPrefix,selection,competitionKey,null);
        }
    }
    public record RunView(UUID id, String status, JsonNode result, String errorCode, Instant createdAt, Instant updatedAt) {}
    record WorkRequest(RecommendationInput input, UUID messageId, long sessionRevision) {}
    record StoredRun(RunView view, JsonNode request) {}
}
