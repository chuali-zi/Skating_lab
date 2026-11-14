package com.skatingcoach.coach;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skatingcoach.scoring.ScoringModels;
import com.skatingcoach.scoring.ScoringService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The model proposes elements; only server-held rules and the calculator establish scores. */
@Component
public class CoachResponseValidator {
    private final ObjectMapper mapper;
    private final ScoringService scoring = new ScoringService();

    public CoachResponseValidator(ObjectMapper mapper) { this.mapper = mapper; }

    public ObjectNode validate(String output, Set<String> permittedEvidence, JsonNode trustedPlanBody,
            List<ScoringModels.Element> completedPrefix, UUID sourcePlanId, ScoringModels.SelectionPolicy selection) {
        try {
            String text = output.strip();
            if (text.startsWith("```")) {
                int newline = text.indexOf('\n');
                int closing = text.lastIndexOf("```");
                if (newline < 0 || closing <= newline) throw invalid();
                text = text.substring(newline + 1, closing).strip();
            }
            JsonNode parsed = mapper.readTree(text);
            if (!parsed.isObject() || !parsed.path("explanation").isTextual() || !parsed.path("candidates").isArray()) throw invalid();
            ScoringModels.RuleSet rules = trustedPlanBody != null && trustedPlanBody.path("ruleSet").isObject()
                    ? mapper.treeToValue(trustedPlanBody.path("ruleSet"), ScoringModels.RuleSet.class) : null;
            if (selection == null && trustedPlanBody != null && trustedPlanBody.path("selection").isObject()) {
                selection = mapper.treeToValue(trustedPlanBody.path("selection"), ScoringModels.SelectionPolicy.class);
            }
            ObjectNode result = mapper.createObjectNode();
            result.put("explanation", parsed.path("explanation").asText());
            ArrayNode candidates = result.putArray("candidates");
            for (JsonNode candidate : parsed.path("candidates")) {
                if (!candidate.path("title").isTextual() || !candidate.path("body").path("elements").isArray()
                        || !candidate.path("evidenceIds").isArray()) throw invalid();
                for (JsonNode reference : candidate.path("evidenceIds")) {
                    if (!reference.isTextual() || !permittedEvidence.contains(reference.asText())) throw invalid();
                }
                List<ScoringModels.Element> elements = mapper.convertValue(candidate.path("body").path("elements"), new TypeReference<>() {});
                ScoringModels.Evaluation evaluation;
                if (completedPrefix != null) {
                    if (sourcePlanId == null) throw invalid();
                    var recovery = scoring.evaluateRecovery(new ScoringModels.RecoveryRequest(completedPrefix, elements, rules, selection));
                    if (!recovery.prefixPreserved()) throw invalid();
                    evaluation = recovery.evaluation();
                } else {
                    evaluation = scoring.evaluate(new ScoringModels.EvaluationRequest(elements, rules, selection));
                }
                if (evaluation.hasViolations()) throw invalid();
                ObjectNode clean = candidates.addObject();
                clean.put("title", candidate.path("title").asText());
                clean.put("kind", completedPrefix == null ? "MAIN" : "RECOVERY");
                clean.put("rationale", candidate.path("rationale").asText(""));
                clean.set("evidenceIds", candidate.path("evidenceIds").deepCopy());
                ObjectNode body = clean.putObject("body");
                body.set("elements", mapper.valueToTree(elements));
                body.put("notes", candidate.path("body").path("notes").asText(""));
                body.set("ruleSet", mapper.valueToTree(rules));
                body.set("selection", mapper.valueToTree(selection));
                if (completedPrefix != null) {
                    body.set("completedPrefix", mapper.valueToTree(completedPrefix));
                    body.put("sourcePlanId", sourcePlanId.toString());
                }
                clean.set("evaluation", mapper.valueToTree(evaluation));
            }
            ArrayNode unknowns = result.putArray("unknowns");
            if (parsed.path("unknowns").isArray()) {
                for (JsonNode unknown : parsed.path("unknowns")) if (unknown.isTextual()) unknowns.add(unknown.asText());
            }
            if (rules == null) unknowns.add("尚未选择可执行规则快照，未给出确定 BV 或全面规则核验结论。");
            return result;
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw invalid();
        }
    }

    private IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_COACH_OUTPUT"); }
}
