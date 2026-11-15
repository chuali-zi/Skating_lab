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
                if (newline < 0 || closing <= newline) throw invalid("JSON_SYNTAX", "返回完整JSON对象，不要留下未闭合的代码围栏。");
                text = text.substring(newline + 1, closing).strip();
            }
            JsonNode parsed = mapper.readTree(text);
            if (parsed == null || !parsed.isObject() || !parsed.path("explanation").isTextual() || !parsed.path("candidates").isArray()) throw invalid("ROOT_SCHEMA", "根对象必须包含字符串explanation和数组candidates。");
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
                        || !candidate.path("evidenceIds").isArray()) throw invalid("CANDIDATE_SCHEMA", "每个候选必须含title字符串、body.elements数组和evidenceIds数组。");
                for (JsonNode reference : candidate.path("evidenceIds")) {
                    if (!reference.isTextual() || !permittedEvidence.contains(reference.asText())) throw invalid("EVIDENCE_NOT_ALLOWED", "evidenceIds只能逐字使用evidenceIndex中的完整id，不使用sourceId、缩写或新造ID。");
                }
                List<ScoringModels.Element> elements = mapper.convertValue(candidate.path("body").path("elements"), new TypeReference<>() {});
                ScoringModels.Evaluation evaluation;
                if (completedPrefix != null) {
                    if (sourcePlanId == null) throw invalid("SOURCE_PLAN_REQUIRED", "预案缺少可信的主方案关联，不能生成预案。");
                    var recovery = scoring.evaluateRecovery(new ScoringModels.RecoveryRequest(completedPrefix, elements, rules, selection));
                    if (!recovery.prefixPreserved()) throw invalid("PREFIX_CHANGED", "body.elements必须是完整序列：开头逐行原样复制completedPrefix的id/type/jumps/plannedStartSeconds，然后追加后缀；不能只返回后缀。");
                    evaluation = recovery.evaluation();
                } else {
                    evaluation = scoring.evaluate(new ScoringModels.EvaluationRequest(elements, rules, selection));
                }
                if (evaluation.hasViolations()) {
                    String codes=evaluation.checks().stream().filter(check -> check.status() == ScoringModels.CheckStatus.VIOLATED)
                            .map(ScoringModels.CheckResult::code).distinct().collect(java.util.stream.Collectors.joining("_"));
                    throw invalid("SCORING_VIOLATION_" + codes, "候选违反服务端检查：" + codes
                            + "。只用selection中对应类型的完整候选，不把单跳拼成未选连跳；SINGLE每行只能一个动作。保持时间非递减、行ID唯一；按照规则快照修正，不要改写规则。");
                }
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
        } catch (ValidationFailure error) {
            throw error;
        } catch (JsonProcessingException error) {
            throw invalid("JSON_SYNTAX", "返回可解析的完整JSON，不要附加说明文字。");
        } catch (IllegalArgumentException error) {
            throw invalid("ELEMENT_SCHEMA", "动作字段为id、type、jumps和plannedStartSeconds；type只能是SINGLE/COMBINATION/SEQUENCE，时间为数字或null。");
        }
    }

    private ValidationFailure invalid(String code, String feedback) { return new ValidationFailure(code, feedback); }

    /** Only fixed reason codes and calculator check codes; never include raw model output in errors/logs. */
    public static final class ValidationFailure extends IllegalArgumentException {
        private final String code;
        private final String feedback;
        private ValidationFailure(String code, String feedback) { super("INVALID_COACH_OUTPUT_" + code); this.code = code; this.feedback = feedback; }
        public String code() { return code; }
        public String feedback() { return feedback; }
    }
}
