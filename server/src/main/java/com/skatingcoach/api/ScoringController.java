package com.skatingcoach.api;

import com.skatingcoach.scoring.ScoringModels;
import com.skatingcoach.scoring.ScoringService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/scoring")
public class ScoringController {
    private final ScoringService scoring = new ScoringService();

    @PostMapping("/evaluate")
    public ScoringModels.Evaluation evaluate(@RequestBody ScoringModels.EvaluationRequest input) {
        return scoring.evaluate(input);
    }

    @PostMapping("/recovery")
    public ScoringModels.RecoveryEvaluation recovery(@RequestBody ScoringModels.RecoveryRequest input) {
        return scoring.evaluateRecovery(input);
    }

    @PostMapping("/compare")
    public ScoringModels.Comparison compare(@RequestBody ScoringModels.ComparisonRequest input) {
        return scoring.compare(input);
    }

    @PostMapping("/default-failure")
    public org.springframework.http.ResponseEntity<ScoringModels.FailureScenario> defaultFailure(@RequestBody ScoringModels.EvaluationRequest input) {
        return org.springframework.http.ResponseEntity.of(scoring.defaultFailureScenario(input));
    }
}
