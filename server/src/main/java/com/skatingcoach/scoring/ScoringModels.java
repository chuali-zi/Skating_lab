package com.skatingcoach.scoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Internal calculation contracts, not a required user input format. */
public final class ScoringModels {
    private ScoringModels() { }

    public enum ElementType { SINGLE, COMBINATION, SEQUENCE }
    public enum CheckStatus { SATISFIED, VIOLATED, UNKNOWN }
    public enum AwardOrder { FIRST, LAST }

    public record Element(String id, ElementType type, List<String> jumps,
                          BigDecimal plannedStartSeconds) { }

    /** A null limit is unknown; unrestricted=true explicitly means no limit. */
    public record CountLimit(Integer maximum, boolean unrestricted) {
        public static CountLimit atMost(int maximum) { return new CountLimit(maximum, false); }
        public static CountLimit unlimited() { return new CountLimit(null, true); }
    }

    public record Limits(CountLimit elements, CountLimit combinations, CountLimit sequences,
                         CountLimit jumpsPerElement, Map<String, Integer> jumpOccurrences,
                         boolean jumpOccurrencesComplete, Set<String> requiredJumps) { }

    /**
     * A disabled bonus is explicit. For enabled bonuses, threshold, multiplier,
     * eligibleTypes and awardOrder must be supplied. Null window/cap mean all/no cap.
     * Empty/null eligibleElementKeys means no additional exact-element restriction.
     * lastElementsWindow is applied to positions BEFORE time qualification.
     */
    public record BonusRule(boolean enabled, BigDecimal startsAtSeconds, boolean includeBoundary,
                            BigDecimal multiplier, Set<ElementType> eligibleTypes,
                            Set<String> eligibleElementKeys, Integer lastElementsWindow,
                            Integer maximumAwards, AwardOrder awardOrder) {
        public static BonusRule none() {
            return new BonusRule(false, null, false, BigDecimal.ONE, Set.of(), Set.of(),
                    null, null, AwardOrder.FIRST);
        }
    }

    /** No season, division, base-value table or bonus is implied by this object. */
    public record RuleSet(String id, String version, String sourceReference,
                          Map<String, BigDecimal> baseValues, BonusRule bonus,
                          BigDecimal sequenceMultiplier, Limits limits,
                          Integer scoreScale, RoundingMode roundingMode,
                          boolean allApplicableRulesRepresented, List<String> unresolvedClauses) { }

    /** Null candidate sets mean unspecified, empty sets mean explicitly none. */
    public record SelectionPolicy(Set<String> selectedSingles, Set<String> selectedCombinations,
                                  Set<String> selectedSequences, Set<String> globallyExcludedJumps) { }

    public record EvaluationRequest(List<Element> elements, RuleSet ruleSet,
                                    SelectionPolicy selection) { }

    public record JumpScore(String code, BigDecimal baseValue) { }

    public record ElementScore(String elementId, int position, String elementKey,
                               ElementType type, BigDecimal plannedStartSeconds,
                               List<JumpScore> jumps, BigDecimal baseValue,
                               BigDecimal elementMultiplier, Boolean bonusApplied,
                               BigDecimal bonusMultiplier, BigDecimal evaluatedValue) { }

    public record CheckResult(String code, CheckStatus status, String elementId,
                              String message, String ruleReference) { }

    /**
     * evaluatedTotal is null when scoring or rule coverage is unresolved. Per-row
     * values and knownSubtotal remain a trial calculation under represented terms,
     * never an official score while scoreComplete is false.
     */
    public record Evaluation(String ruleSetId, String ruleVersion, BigDecimal baseTotal,
                             BigDecimal knownSubtotal, BigDecimal evaluatedTotal,
                             boolean scoreComplete, boolean checksComplete,
                             boolean hasViolations, List<ElementScore> elements,
                             List<CheckResult> checks) { }

    /**
     * completedPrefix describes what ACTUALLY happened; proposedElements is the full
     * resulting plan. Only its suffix may change. The caller supplies authoritative
     * completedPrefix, never a model-rewritten copy.
     */
    public record RecoveryRequest(List<Element> completedPrefix, List<Element> proposedElements,
                                  RuleSet ruleSet, SelectionPolicy selection) { }

    public record RecoveryEvaluation(boolean prefixPreserved, int frozenElementCount,
                                     Evaluation evaluation) { }

    public record ComparisonRequest(List<Element> before, List<Element> after,
                                    RuleSet ruleSet, SelectionPolicy selection) { }

    public record ElementChange(String elementId, Integer beforePosition, Integer afterPosition,
                                String beforeKey, String afterKey, BigDecimal beforeTime,
                                BigDecimal afterTime, List<String> changes) { }

    public record Comparison(Evaluation before, Evaluation after, BigDecimal evaluatedBvDelta,
                             List<ElementChange> changes) { }

    public record FailureScenario(String elementId, int position, String plannedElementKey,
                                  List<Element> completedPrefix, List<Element> unchangedSuffix,
                                  String description) { }
}
