package com.skatingcoach.coach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skatingcoach.scoring.ScoringModels;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CoachResponseValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CoachResponseValidator validator = new CoachResponseValidator(mapper);
    private final String response = """
            {"explanation":"本场先求稳","candidates":[{"title":"稳健安排","body":{"elements":[
            {"id":"e1","type":"SINGLE","jumps":["2A"],"plannedStartSeconds":20}],
            "ruleSet":{"invented":true},"notes":"按当前状态"},"rationale":"个人偏好","evidenceIds":["known"]}],"unknowns":[]}
            """;

    @Test void rejectsInventedCitations() {
        assertThatThrownBy(() -> validator.validate(response, Set.of("another"), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void discardsModelSuppliedRulesAndKeepsUnknownScoringHonest() {
        var output = validator.validate(response, Set.of("known"), null, null, null, null);
        var candidate = output.path("candidates").get(0);
        assertThat(candidate.path("body").path("ruleSet").isNull()).isTrue();
        assertThat(candidate.path("evaluation").path("scoreComplete").asBoolean()).isFalse();
        assertThat(candidate.path("evaluation").path("evaluatedTotal").isNull()).isTrue();
        assertThat(output.path("unknowns").isEmpty()).isFalse();
    }

    @Test void cannotRewriteTheActualRecoveryPrefix() {
        var prefix = List.of(new ScoringModels.Element("e1", ScoringModels.ElementType.SINGLE, List.of("1A"), new BigDecimal("20")));
        assertThatThrownBy(() -> validator.validate(response, Set.of("known"), null, prefix, UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void respectsExplicitlyEmptyCandidateSelections() {
        var selection = new ScoringModels.SelectionPolicy(Set.of(), Set.of(), Set.of(), Set.of());
        assertThatThrownBy(() -> validator.validate(response, Set.of("known"), null, null, null, selection))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void acceptsInsufficientEvidenceWithoutFabricatingAPlan() {
        var result = validator.validate("{\"explanation\":\"尚缺比赛规则\",\"candidates\":[],\"unknowns\":[\"比赛规则\"]}", Set.of(), null, null, null, null);
        assertThat(result.path("candidates").isEmpty()).isTrue();
    }
}
