package com.skatingcoach.planning;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skatingcoach.api.ApiException;
import com.skatingcoach.scoring.ScoringModels;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Prevent unknown nested fields from becoming an unmanaged copy of private source data. */
@Component
public class PlanBodyProjector {
    private final ObjectMapper mapper;
    public PlanBodyProjector(ObjectMapper mapper) { this.mapper = mapper; }

    public ObjectNode project(JsonNode proposed) {
        if (proposed == null || !proposed.isObject()) throw invalid();
        try {
            ObjectNode clean = mapper.createObjectNode();
            clean.set("elements", mapper.valueToTree(elements(proposed.path("elements"))));
            clean.set("ruleSet", mapper.valueToTree(proposed.path("ruleSet").isNull() || proposed.path("ruleSet").isMissingNode()
                    ? null : mapper.convertValue(proposed.path("ruleSet"), ScoringModels.RuleSet.class)));
            clean.set("selection", mapper.valueToTree(proposed.path("selection").isNull() || proposed.path("selection").isMissingNode()
                    ? null : mapper.convertValue(proposed.path("selection"), ScoringModels.SelectionPolicy.class)));
            JsonNode prefix = proposed.has("completedPrefix") ? proposed.get("completedPrefix") : proposed.get("actualPrefix");
            if (prefix != null && !prefix.isNull()) clean.set("completedPrefix", mapper.valueToTree(elements(prefix)));
            for (String field : List.of("notes", "failureDescription")) {
                if (proposed.path(field).isTextual()) clean.put(field, proposed.path(field).asText());
            }
            if (proposed.path("sourcePlanId").isTextual()) clean.put("sourcePlanId", UUID.fromString(proposed.path("sourcePlanId").asText()).toString());
            if (proposed.path("sourcePlanRevision").isIntegralNumber()) clean.put("sourcePlanRevision", proposed.path("sourcePlanRevision").asLong());
            if (proposed.hasNonNull("videoReview")) clean.set("videoReview",mapper.valueToTree(mapper.convertValue(proposed.get("videoReview"),com.skatingcoach.video.VideoJobs.ReviewRef.class)));
            return clean;
        } catch (IllegalArgumentException error) { throw invalid(); }
    }

    private List<ScoringModels.Element> elements(JsonNode value) {
        if (!value.isArray()) throw invalid();
        return mapper.convertValue(value, new TypeReference<>() {});
    }

    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PLAN", "方案数据暂时无法处理，请刷新后重试。"); }
}
