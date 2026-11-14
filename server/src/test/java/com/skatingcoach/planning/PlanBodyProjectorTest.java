package com.skatingcoach.planning;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skatingcoach.api.ApiException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlanBodyProjectorTest {
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final PlanBodyProjector projector = new PlanBodyProjector(mapper);

    @Test void stripsUnmanagedNestedCopiesOfPrivateEvidence() throws Exception {
        var input = mapper.readTree("""
                {"elements":[{"id":"e1","type":"SINGLE","jumps":["2A"],"plannedStartSeconds":30,
                "privateEvidence":{"statement":"should not persist"}}],
                "selection":{"selectedSingles":["2A"],"privateTrainingNotes":"do not persist"},
                "ruleSet":{"id":"r","version":"1","privateDocument":"do not persist"},
                "evidence":{"fullSource":"do not persist"},"notes":"user note"}
                """);
        var projected = projector.project(input);
        assertThat(projected.has("evidence")).isFalse();
        assertThat(projected.path("elements").get(0).has("privateEvidence")).isFalse();
        assertThat(projected.path("selection").has("privateTrainingNotes")).isFalse();
        assertThat(projected.path("ruleSet").has("privateDocument")).isFalse();
        assertThat(projected.path("notes").asText()).isEqualTo("user note");
    }

    @Test void revokedPlanCannotEnterRecommendationOrRecoveryContext() {
        var plan = new PlanModels.PlanView(UUID.randomUUID(), "plan", PlanModels.Kind.MAIN, 2,
                mapper.createObjectNode(), Instant.now(), Instant.now(), true);
        assertThatThrownBy(() -> PlanService.requireCurrentContext(plan))
                .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("SOURCE_REVOKED"));
    }

    @Test void acceptsCanonicalRecoveryPrefixAndRejectsUnstructuredPlanBody() throws Exception {
        var projected = projector.project(mapper.readTree("{\"elements\":[],\"actualPrefix\":[],\"sourcePlanId\":\"00000000-0000-0000-0000-000000000001\"}"));
        assertThat(projected.has("completedPrefix")).isTrue();
        assertThat(projected.has("actualPrefix")).isFalse();
        assertThatThrownBy(() -> projector.project(mapper.createArrayNode())).isInstanceOf(ApiException.class);
    }
}
