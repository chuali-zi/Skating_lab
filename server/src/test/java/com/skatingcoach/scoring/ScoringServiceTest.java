package com.skatingcoach.scoring;

import static com.skatingcoach.scoring.ScoringModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScoringServiceTest {
    private final ScoringService service = new ScoringService();

    @Test void thresholdUsesActualTimeAndExplicitBoundary() {
        List<Element> elements = List.of(single("a", "3S", "119.999"), single("b", "3T", "120"), single("c", "2T", "120.001"));
        Evaluation exclusive = evaluate(elements, rules(timed(false, null, null, AwardOrder.FIRST)));
        assertEquals(List.of(false, false, true), exclusive.elements().stream().map(ElementScore::bonusApplied).toList());
        assertDecimal("9.93", exclusive.evaluatedTotal());
        Evaluation inclusive = evaluate(elements, rules(timed(true, null, null, AwardOrder.FIRST)));
        assertTrue(inclusive.elements().get(1).bonusApplied());
        assertDecimal("10.35", inclusive.evaluatedTotal());
    }

    @Test void unknownTimeDoesNotBecomeSecondHalfFromItsPosition() {
        List<Element> elements = List.of(single("a", "3S", "90"), single("b", "3T", null));
        Evaluation evaluation = evaluate(elements, rules(timed(false, null, null, AwardOrder.FIRST)));
        assertNull(evaluation.elements().get(1).bonusApplied());
        assertNull(evaluation.evaluatedTotal());
        assertFalse(evaluation.scoreComplete());
        assertDecimal("4.30", evaluation.knownSubtotal());
        assertDecimal("8.50", evaluation.baseTotal());
    }

    @Test void lastElementWindowAndAwardCapAreSeparateFromTime() {
        List<Element> elements = List.of(single("a", "3S", "125"), single("b", "3T", "130"), single("c", "2T", "140"));
        Evaluation evaluation = evaluate(elements, rules(timed(false, 2, 1, AwardOrder.LAST)));
        assertEquals(List.of(false, false, true), evaluation.elements().stream().map(ElementScore::bonusApplied).toList());
        assertDecimal("9.93", evaluation.evaluatedTotal());
    }

    @Test void unknownTimePropagatesThroughLimitedBonusSlots() {
        List<Element> elements = List.of(single("a", "3S", null), single("b", "3T", "140"), single("c", "2T", "150"));
        Evaluation evaluation = evaluate(elements, rules(timed(false, null, 1, AwardOrder.FIRST)));
        assertNull(evaluation.elements().get(0).bonusApplied());
        assertNull(evaluation.elements().get(1).bonusApplied());
        assertEquals(false, evaluation.elements().get(2).bonusApplied());
        assertNull(evaluation.evaluatedTotal());
    }

    @Test void irrelevantUnknownTimeOutsideBonusWindowDoesNotPreventScoring() {
        List<Element> elements = List.of(single("a", "3S", null), single("b", "3T", "140"));
        Evaluation evaluation = evaluate(elements, rules(timed(false, 1, 1, AwardOrder.LAST)));
        assertEquals(false, evaluation.elements().get(0).bonusApplied());
        assertDecimal("8.92", evaluation.evaluatedTotal());
    }

    @Test void roundsOnlyAfterSummingCombinationAndApplyingMultiplier() {
        RuleSet rule = new RuleSet("fixture", "rounding", "test-fixture-only", Map.of("X", decimal("0.105"), "Y", decimal("0.105")),
                timed(false, null, null, AwardOrder.FIRST), BigDecimal.ONE, unlimited(), 2, RoundingMode.HALF_UP, true, List.of());
        Evaluation evaluation = evaluate(List.of(combo("a", List.of("X", "Y"), "130")), rule);
        assertDecimal("0.210", evaluation.baseTotal());
        assertDecimal("0.23", evaluation.evaluatedTotal());
    }

    @Test void singleAndOrderedCombinationSelectionsAreIndependent() {
        List<Element> elements = List.of(combo("a", List.of("3S", "3T"), "100"));
        SelectionPolicy policy = new SelectionPolicy(Set.of("3S"), Set.of("3S+3T"), Set.of(), Set.of());
        Evaluation evaluation = service.evaluate(new EvaluationRequest(elements, rules(BonusRule.none()), policy));
        assertFalse(evaluation.hasViolations());
        assertTrue(evaluation.checksComplete());
        assertDecimal("8.50", evaluation.evaluatedTotal());
        Evaluation reversed = service.evaluate(new EvaluationRequest(List.of(combo("a", List.of("3T", "3S"), "100")), rules(BonusRule.none()), policy));
        assertTrue(reversed.hasViolations());
        assertTrue(has(reversed, "SELECTED_CANDIDATE", CheckStatus.VIOLATED));
    }

    @Test void explicitGlobalExclusionAlsoAppliesInsideASelectedCombination() {
        Evaluation evaluation = service.evaluate(new EvaluationRequest(List.of(combo("a", List.of("3S", "3T"), "100")),
                rules(BonusRule.none()), new SelectionPolicy(Set.of(), Set.of("3S+3T"), Set.of(), Set.of("3T"))));
        assertTrue(has(evaluation, "GLOBAL_JUMP_EXCLUSION", CheckStatus.VIOLATED));
    }

    @Test void missingRuleSetDoesNotFabricateZeroAsACompleteScore() {
        Evaluation evaluation = evaluate(List.of(single("a", "3S", "100")), null);
        assertNull(evaluation.baseTotal());
        assertNull(evaluation.evaluatedTotal());
        assertFalse(evaluation.scoreComplete());
        assertFalse(evaluation.checksComplete());
        assertTrue(has(evaluation, "RULESET_MISSING", CheckStatus.UNKNOWN));
    }

    @Test void eulerAliasUsesSuppliedZeroValueAndNeverOldSeasonDefaults() {
        Evaluation evaluation = evaluate(List.of(combo("a", List.of("3s", "Eu", "2t"), "100")), rules(BonusRule.none()));
        assertDecimal("5.60", evaluation.evaluatedTotal());
        assertEquals("3S+1Eu+2T", evaluation.elements().get(0).elementKey());
        RuleSet absent = new RuleSet("fixture", "without-euler", "test-fixture-only", Map.of("3S", decimal("4.30"), "2T", decimal("1.30")),
                BonusRule.none(), BigDecimal.ONE, unlimited(), 2, RoundingMode.HALF_UP, true, List.of());
        assertNull(evaluate(List.of(combo("a", List.of("3S", "Eu", "2T"), "100")), absent).evaluatedTotal());
    }

    @Test void conflictingAliasesRemainUnknownInsteadOfLastWriteWins() {
        RuleSet rule = new RuleSet("fixture", "conflict", "test-fixture-only", Map.of("Eu", decimal("0.50"), "1Eu", BigDecimal.ZERO),
                BonusRule.none(), BigDecimal.ONE, unlimited(), 2, RoundingMode.HALF_UP, true, List.of());
        Evaluation evaluation = evaluate(List.of(single("a", "1Eu", "100")), rule);
        assertNull(evaluation.evaluatedTotal());
        assertTrue(has(evaluation, "BASE_VALUE_CONFLICT", CheckStatus.UNKNOWN));
    }

    @Test void unresolvedRulesKeepTrialArithmeticWithoutClaimingACompleteBv() {
        RuleSet base = rules(BonusRule.none());
        RuleSet rule = new RuleSet(base.id(), base.version(), base.sourceReference(), base.baseValues(), base.bonus(), base.sequenceMultiplier(),
                null, base.scoreScale(), base.roundingMode(), false, List.of("组别重复例外仍待核对"));
        Evaluation evaluation = evaluate(List.of(single("a", "3S", "100")), rule);
        assertFalse(evaluation.scoreComplete());
        assertNull(evaluation.evaluatedTotal());
        assertDecimal("4.30", evaluation.knownSubtotal());
        assertFalse(evaluation.checksComplete());
        assertTrue(has(evaluation, "UNRESOLVED_CLAUSE", CheckStatus.UNKNOWN));
    }

    @Test void violationDoesNotInventAnInvalidElementPenalty() {
        RuleSet base = rules(BonusRule.none());
        Limits limits = new Limits(CountLimit.atMost(7), CountLimit.atMost(3), CountLimit.atMost(1), CountLimit.atMost(3),
                Map.of("3S", 1), true, Set.of());
        RuleSet rule = new RuleSet(base.id(), base.version(), base.sourceReference(), base.baseValues(), base.bonus(), base.sequenceMultiplier(),
                limits, base.scoreScale(), base.roundingMode(), true, List.of());
        Evaluation evaluation = evaluate(List.of(single("a", "3S", "100"), single("b", "3S", "110")), rule);
        assertTrue(has(evaluation, "JUMP_REPETITION", CheckStatus.VIOLATED));
        assertNull(evaluation.evaluatedTotal());
        assertTrue(has(evaluation, "SCORING_CONSEQUENCE_UNKNOWN", CheckStatus.UNKNOWN));
    }

    @Test void recoveryProtectsActualPrefixIncludingTimeAndOrder() {
        Element actual = single("a", "2T", "90.0");
        SelectionPolicy selection = new SelectionPolicy(Set.of("3S"), Set.of(), Set.of(), Set.of());
        RecoveryEvaluation valid = service.evaluateRecovery(new RecoveryRequest(List.of(actual),
                List.of(single("a", "2T", "90.00"), single("b", "3S", "100")), rules(BonusRule.none()), selection));
        assertTrue(valid.prefixPreserved());
        assertFalse(valid.evaluation().hasViolations());
        assertDecimal("5.60", valid.evaluation().evaluatedTotal());
        RecoveryEvaluation alteredJump = service.evaluateRecovery(new RecoveryRequest(List.of(actual),
                List.of(single("a", "3T", "90"), single("b", "3S", "100")), rules(BonusRule.none()), selection));
        assertFalse(alteredJump.prefixPreserved());
        assertNull(alteredJump.evaluation().evaluatedTotal());
        RecoveryEvaluation alteredTime = service.evaluateRecovery(new RecoveryRequest(List.of(actual),
                List.of(single("a", "2T", "130"), single("b", "3S", "140")), rules(BonusRule.none()), selection));
        assertFalse(alteredTime.prefixPreserved());
    }

    @Test void defaultFailureChoosesFirstTiedCombinationAndFreezesCompletedFirstJump() {
        List<Element> elements = List.of(single("a", "2T", "40"), combo("b", List.of("3S", "3T"), "80"),
                combo("c", List.of("3T", "3S"), "100"));
        FailureScenario scenario = service.defaultFailureScenario(new EvaluationRequest(elements, rules(BonusRule.none()), selection(elements))).orElseThrow();
        assertEquals("b", scenario.elementId());
        assertEquals(2, scenario.completedPrefix().size());
        assertEquals(List.of("3S"), scenario.completedPrefix().get(1).jumps());
        assertEquals(ElementType.SINGLE, scenario.completedPrefix().get(1).type());
        assertEquals(List.of(elements.get(2)), scenario.unchangedSuffix());
    }

    @Test void defaultFailureDoesNotRankAnUnknownCombinationAsIfItsBvWereKnown() {
        List<Element> elements = List.of(combo("a", List.of("3S", "3T"), "80"), combo("b", List.of("3T", "3S"), null));
        assertTrue(service.defaultFailureScenario(new EvaluationRequest(elements, rules(timed(false, null, null, AwardOrder.FIRST)), selection(elements))).isEmpty());
    }

    @Test void comparisonReportsChangesAndAnExactDeltaWithoutRankingPersonalBenefit() {
        List<Element> before = List.of(single("a", "3S", "100"));
        List<Element> after = List.of(single("a", "3S", "130"));
        Comparison comparison = service.compare(new ComparisonRequest(before, after, rules(timed(false, null, null, AwardOrder.FIRST)), selection(before)));
        assertDecimal("0.43", comparison.evaluatedBvDelta());
        assertEquals(List.of("TIME_CHANGED"), comparison.changes().get(0).changes());
    }

    @Test void sequenceFactorMustBeExplicitAndAppliesToWholeElement() {
        Element sequence = new Element("a", ElementType.SEQUENCE, List.of("3S", "2T"), decimal("100"));
        RuleSet base = rules(BonusRule.none());
        RuleSet rule = new RuleSet(base.id(), base.version(), base.sourceReference(), base.baseValues(), base.bonus(), decimal("0.8"),
                base.limits(), 2, RoundingMode.HALF_UP, true, List.of());
        assertDecimal("4.48", evaluate(List.of(sequence), rule).evaluatedTotal());
        RuleSet missing = new RuleSet(base.id(), base.version(), base.sourceReference(), base.baseValues(), base.bonus(), null,
                base.limits(), 2, RoundingMode.HALF_UP, true, List.of());
        assertNull(evaluate(List.of(sequence), missing).evaluatedTotal());
    }

    private Evaluation evaluate(List<Element> elements, RuleSet rules) {
        return service.evaluate(new EvaluationRequest(elements, rules, selection(elements)));
    }

    private static SelectionPolicy selection(List<Element> elements) {
        Set<String> singles = new java.util.HashSet<>();
        Set<String> combinations = new java.util.HashSet<>();
        Set<String> sequences = new java.util.HashSet<>();
        for (Element element : elements) switch (element.type()) {
            case SINGLE -> singles.add(ScoringService.elementKey(element));
            case COMBINATION -> combinations.add(ScoringService.elementKey(element));
            case SEQUENCE -> sequences.add(ScoringService.elementKey(element));
        }
        return new SelectionPolicy(singles, combinations, sequences, Set.of());
    }

    /** Synthetic arithmetic fixtures only: these are NOT an active competition rule set. */
    private static RuleSet rules(BonusRule bonus) {
        return new RuleSet("test-fixture", "1", "test-fixture-only", Map.of("3S", decimal("4.30"), "3T", decimal("4.20"),
                "2T", decimal("1.30"), "3Lz", decimal("5.90"), "1Eu", BigDecimal.ZERO), bonus, BigDecimal.ONE,
                unlimited(), 2, RoundingMode.HALF_UP, true, List.of());
    }

    private static Limits unlimited() {
        return new Limits(CountLimit.unlimited(), CountLimit.unlimited(), CountLimit.unlimited(), CountLimit.unlimited(),
                Map.of(), true, Set.of());
    }

    private static BonusRule timed(boolean boundary, Integer window, Integer cap, AwardOrder order) {
        return new BonusRule(true, decimal("120"), boundary, decimal("1.1"), Set.of(ElementType.values()), Set.of(), window, cap, order);
    }

    private static Element single(String id, String code, String seconds) {
        return new Element(id, ElementType.SINGLE, List.of(code), seconds == null ? null : decimal(seconds));
    }
    private static Element combo(String id, List<String> jumps, String seconds) {
        return new Element(id, ElementType.COMBINATION, jumps, seconds == null ? null : decimal(seconds));
    }
    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
    private static void assertDecimal(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, decimal(expected).compareTo(actual), () -> expected + " != " + actual);
    }
    private static boolean has(Evaluation evaluation, String code, CheckStatus status) {
        return evaluation.checks().stream().anyMatch(check -> check.code().equals(code) && check.status() == status);
    }
}
