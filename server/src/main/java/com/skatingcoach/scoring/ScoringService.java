package com.skatingcoach.scoring;

import static com.skatingcoach.scoring.ScoringModels.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic arithmetic and explicit checks; never a personal ranking model. */
public final class ScoringService {
    private static final Pattern STANDARD_CODE = Pattern.compile("(?i)^(\\d+)(T|S|LO|F|LZ|A|EU)$");

    public Evaluation evaluate(EvaluationRequest request) {
        Objects.requireNonNull(request, "evaluation request");
        return evaluateInternal(safe(request.elements()), request.ruleSet(), request.selection(), 0);
    }

    public RecoveryEvaluation evaluateRecovery(RecoveryRequest request) {
        Objects.requireNonNull(request, "recovery request");
        List<Element> prefix = safe(request.completedPrefix());
        List<Element> proposed = safe(request.proposedElements());
        boolean prefixProvided = request.completedPrefix() != null;
        boolean preserved = prefixProvided && proposed.size() >= prefix.size();
        for (int i = 0; preserved && i < prefix.size(); i++) {
            preserved = sameElement(prefix.get(i), proposed.get(i));
        }
        Evaluation evaluation = evaluateInternal(proposed, request.ruleSet(), request.selection(), prefix.size());
        List<CheckResult> checks = new ArrayList<>(evaluation.checks());
        checks.add(new CheckResult("RECOVERY_PREFIX", preserved ? CheckStatus.SATISFIED : CheckStatus.VIOLATED,
                null, preserved ? "已发生动作的顺序、类型、动作和时间保持不变。" :
                "预案必须保留服务端记录的实际完成前缀，不能改写已发生动作。", "actual-completed-prefix"));
        evaluation = new Evaluation(evaluation.ruleSetId(), evaluation.ruleVersion(), evaluation.baseTotal(),
                evaluation.knownSubtotal(), preserved ? evaluation.evaluatedTotal() : null,
                preserved && evaluation.scoreComplete(), preserved && evaluation.checksComplete(),
                !preserved || evaluation.hasViolations(), evaluation.elements(), List.copyOf(checks));
        return new RecoveryEvaluation(preserved, prefix.size(), evaluation);
    }

    public Comparison compare(ComparisonRequest request) {
        Objects.requireNonNull(request, "comparison request");
        List<Element> before = safe(request.before());
        List<Element> after = safe(request.after());
        Evaluation oldScore = evaluate(new EvaluationRequest(before, request.ruleSet(), request.selection()));
        Evaluation newScore = evaluate(new EvaluationRequest(after, request.ruleSet(), request.selection()));
        Map<String, Integer> oldPositions = positions(before);
        Map<String, Integer> newPositions = positions(after);
        Set<String> ids = new LinkedHashSet<>(oldPositions.keySet());
        ids.addAll(newPositions.keySet());
        List<ElementChange> changes = new ArrayList<>();
        for (String id : ids) {
            Integer oldPosition = oldPositions.get(id);
            Integer newPosition = newPositions.get(id);
            Element oldElement = oldPosition == null ? null : before.get(oldPosition);
            Element newElement = newPosition == null ? null : after.get(newPosition);
            List<String> kinds = new ArrayList<>();
            if (oldPosition == null) kinds.add("ADDED");
            else if (newPosition == null) kinds.add("REMOVED");
            else {
                if (!oldPosition.equals(newPosition)) kinds.add("MOVED");
                if (!Objects.equals(elementKey(oldElement), elementKey(newElement))) kinds.add("JUMPS_CHANGED");
                if (!Objects.equals(typeOf(oldElement), typeOf(newElement))) kinds.add("TYPE_CHANGED");
                if (!sameNumber(timeOf(oldElement), timeOf(newElement))) kinds.add("TIME_CHANGED");
            }
            if (!kinds.isEmpty()) changes.add(new ElementChange(id, oneBased(oldPosition), oneBased(newPosition),
                    oldElement == null ? null : elementKey(oldElement), newElement == null ? null : elementKey(newElement),
                    timeOf(oldElement), timeOf(newElement), List.copyOf(kinds)));
        }
        BigDecimal delta = oldScore.evaluatedTotal() == null || newScore.evaluatedTotal() == null ? null :
                newScore.evaluatedTotal().subtract(oldScore.evaluatedTotal());
        return new Comparison(oldScore, newScore, delta, List.copyOf(changes));
    }

    /** Highest exactly scored combination; ties select the earliest one. No invented timing. */
    public Optional<FailureScenario> defaultFailureScenario(EvaluationRequest request) {
        Evaluation evaluation = evaluate(request);
        List<Element> elements = safe(request.elements());
        if (request.ruleSet() == null || !request.ruleSet().allApplicableRulesRepresented()
                || !safe(request.ruleSet().unresolvedClauses()).isEmpty() || evaluation.hasViolations()) return Optional.empty();
        if (elements.stream().anyMatch(e -> e == null || blank(e.id()))) return Optional.empty();
        int selected = -1;
        BigDecimal best = null;
        for (int i = 0; i < elements.size(); i++) {
            Element element = elements.get(i);
            if (typeOf(element) != ElementType.COMBINATION || safeJumps(element).size() < 2) continue;
            BigDecimal value = evaluation.elements().get(i).evaluatedValue();
            if (value == null) return Optional.empty();
            if (best == null || value.compareTo(best) > 0) { selected = i; best = value; }
        }
        if (selected < 0) return Optional.empty();
        Element failed = elements.get(selected);
        List<Element> prefix = new ArrayList<>(elements.subList(0, selected));
        prefix.add(new Element(failed.id(), ElementType.SINGLE,
                List.of(normalizeJumpCode(failed.jumps().get(0))), failed.plannedStartSeconds()));
        return Optional.of(new FailureScenario(failed.id(), selected + 1, elementKey(failed), List.copyOf(prefix),
                List.copyOf(elements.subList(selected + 1, elements.size())),
                "假设该连跳第一跳已完成、后续跳未接上；此前动作按原计划完成。"));
    }

    public static String normalizeJumpCode(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("Eu")) return "1Eu";
        Matcher matcher = STANDARD_CODE.matcher(trimmed);
        if (!matcher.matches()) return trimmed;
        String family = switch (matcher.group(2).toUpperCase(Locale.ROOT)) {
            case "LO" -> "Lo";
            case "LZ" -> "Lz";
            case "EU" -> "Eu";
            default -> matcher.group(2).toUpperCase(Locale.ROOT);
        };
        return matcher.group(1) + family;
    }

    public static String elementKey(Element element) {
        return String.join("+", safeJumps(element).stream().map(ScoringService::normalizeJumpCode).toList());
    }

    private Evaluation evaluateInternal(List<Element> elements, RuleSet rules, SelectionPolicy selection,
                                        int frozenPrefixLength) {
        List<CheckResult> checks = new ArrayList<>();
        String ref = rules == null ? null : rules.sourceReference();
        if (rules == null) add(checks, "RULESET_MISSING", CheckStatus.UNKNOWN, null,
                "尚未提供适用的规则包，不能确定正式 BV 或完整规则检查。", ref);
        else {
            if (blank(rules.id()) || blank(rules.version()) || blank(rules.sourceReference()))
                add(checks, "RULE_PROVENANCE_MISSING", CheckStatus.UNKNOWN, null, "规则包缺少标识、版本或来源。", ref);
            if (!rules.allApplicableRulesRepresented()) add(checks, "RULE_COVERAGE_INCOMPLETE", CheckStatus.UNKNOWN,
                    null, "当前工具只检查已表达条款，尚不能宣称覆盖全部适用规则。", ref);
            for (String unresolved : safe(rules.unresolvedClauses())) add(checks, "UNRESOLVED_CLAUSE",
                    CheckStatus.UNKNOWN, null, unresolved == null ? "存在未解决条款。" : unresolved, ref);
        }
        Map<String, BigDecimal> values = canonicalValues(rules, checks);
        boolean roundingKnown = rules != null && rules.scoreScale() != null && rules.scoreScale() >= 0
                && rules.roundingMode() != null;
        if (!roundingKnown) add(checks, "ROUNDING_RULE_UNKNOWN", CheckStatus.UNKNOWN, null,
                "分值保留位数或舍入方式尚未明确。", ref);
        List<Boolean> bonuses = bonusDecisions(elements, rules == null ? null : rules.bonus(), checks, ref);
        List<ElementScore> rows = new ArrayList<>();
        BigDecimal rawTotal = BigDecimal.ZERO;
        BigDecimal knownSubtotal = BigDecimal.ZERO;
        boolean allBaseKnown = true;
        boolean allScoresKnown = rules != null && roundingKnown && rules.allApplicableRulesRepresented()
                && safe(rules.unresolvedClauses()).isEmpty();
        Set<String> ids = new HashSet<>();
        BigDecimal previousKnownTime = null;
        for (int i = 0; i < elements.size(); i++) {
            Element element = elements.get(i);
            String id = idOf(element, i);
            List<String> jumps = safeJumps(element);
            if (element == null || blank(element.id())) add(checks, "ELEMENT_ID_MISSING", CheckStatus.UNKNOWN,
                    id, "动作行缺少稳定标识。", ref);
            if (!ids.add(id)) add(checks, "ELEMENT_ID_DUPLICATE", CheckStatus.VIOLATED, id,
                    "动作行标识重复，无法可靠关联编辑及预案。", ref);
            if (typeOf(element) == null) add(checks, "ELEMENT_TYPE_UNKNOWN", CheckStatus.UNKNOWN, id,
                    "动作行类型尚未确定。", ref);
            if (jumps.isEmpty()) add(checks, "EMPTY_ELEMENT", CheckStatus.UNKNOWN, id, "动作行尚无可计分动作。", ref);
            if (typeOf(element) == ElementType.SINGLE && jumps.size() != 1)
                add(checks, "SINGLE_ARITY", CheckStatus.VIOLATED, id, "独立单跳行必须只包含一个动作。", ref);
            if ((typeOf(element) == ElementType.COMBINATION || typeOf(element) == ElementType.SEQUENCE) && jumps.size() < 2)
                add(checks, "GROUP_ARITY", CheckStatus.VIOLATED, id, "连跳或连续跳行需要至少两个有序动作。", ref);
            BigDecimal time = timeOf(element);
            if (time != null && time.signum() < 0) add(checks, "NEGATIVE_TIME", CheckStatus.VIOLATED, id,
                    "计划时间早于节目开始，需修正时间安排。", ref);
            if (time != null && previousKnownTime != null && time.compareTo(previousKnownTime) < 0)
                add(checks, "TIME_ORDER", CheckStatus.VIOLATED, id, "动作时间与方案顺序矛盾。", ref);
            if (time != null) previousKnownTime = time;
            if (i >= frozenPrefixLength) checkSelection(element, id, selection, checks);

            BigDecimal base = jumps.isEmpty() ? null : BigDecimal.ZERO;
            List<JumpScore> jumpScores = new ArrayList<>();
            for (String jump : jumps) {
                String code = normalizeJumpCode(jump);
                BigDecimal value = values.get(code);
                jumpScores.add(new JumpScore(code, value));
                if (value == null) { base = null; add(checks, "BASE_VALUE_UNKNOWN", CheckStatus.UNKNOWN, id,
                        "缺少动作 " + code + " 在当前规则版本下的基础分。", ref); }
                else if (base != null) base = base.add(value);
            }
            if (base == null) allBaseKnown = false;
            else rawTotal = rawTotal.add(base);
            BigDecimal elementMultiplier = switch (typeOf(element) == null ? "UNKNOWN" : typeOf(element).name()) {
                case "SINGLE", "COMBINATION" -> BigDecimal.ONE;
                case "SEQUENCE" -> rules == null ? null : rules.sequenceMultiplier();
                default -> null;
            };
            if (elementMultiplier == null || elementMultiplier.signum() < 0) {
                elementMultiplier = null;
                add(checks, "ELEMENT_MULTIPLIER_UNKNOWN", CheckStatus.UNKNOWN, id,
                        "当前动作类型的计值系数尚未明确。", ref);
            }
            Boolean awarded = bonuses.get(i);
            BigDecimal bonusMultiplier = awarded == null ? null : !awarded ? BigDecimal.ONE : rules.bonus().multiplier();
            BigDecimal evaluated = null;
            if (base != null && elementMultiplier != null && bonusMultiplier != null && roundingKnown) {
                try {
                    evaluated = base.multiply(elementMultiplier).multiply(bonusMultiplier)
                            .setScale(rules.scoreScale(), rules.roundingMode());
                } catch (ArithmeticException exception) {
                    add(checks, "ROUNDING_UNRESOLVED", CheckStatus.UNKNOWN, id,
                            "所选舍入策略无法精确表示当前计值，需明确规则解释。", ref);
                }
            }
            if (evaluated == null) allScoresKnown = false;
            else knownSubtotal = knownSubtotal.add(evaluated);
            rows.add(new ElementScore(id, i + 1, elementKey(element), typeOf(element), time,
                    List.copyOf(jumpScores), base, elementMultiplier, awarded, bonusMultiplier, evaluated));
        }
        checkLimits(elements, rules == null ? null : rules.limits(), checks, ref);
        Set<String> scoringViolations = Set.of("SINGLE_ARITY", "GROUP_ARITY", "NEGATIVE_TIME", "TIME_ORDER",
                "ELEMENT_COUNT", "COMBINATION_COUNT", "SEQUENCE_COUNT", "JUMPS_PER_ELEMENT", "JUMP_REPETITION", "REQUIRED_JUMP");
        if (checks.stream().anyMatch(c -> c.status() == CheckStatus.VIOLATED && scoringViolations.contains(c.code()))) {
            allScoresKnown = false;
            add(checks, "SCORING_CONSEQUENCE_UNKNOWN", CheckStatus.UNKNOWN, null,
                    "存在违反已知规则的动作；相关无效计值或扣分处理尚未建模，逐行试算不能作为确定总 BV。", ref);
        }
        boolean hasViolations = checks.stream().anyMatch(c -> c.status() == CheckStatus.VIOLATED);
        boolean checksComplete = checks.stream().noneMatch(c -> c.status() == CheckStatus.UNKNOWN);
        return new Evaluation(rules == null ? null : rules.id(), rules == null ? null : rules.version(),
                allBaseKnown ? rawTotal : null, knownSubtotal, allScoresKnown ? knownSubtotal : null,
                allScoresKnown, checksComplete, hasViolations, List.copyOf(rows), List.copyOf(checks));
    }

    private List<Boolean> bonusDecisions(List<Element> elements, BonusRule rule,
                                         List<CheckResult> checks, String ref) {
        List<Boolean> result = new ArrayList<>(Collections.nCopies(elements.size(), null));
        if (rule == null) {
            add(checks, "BONUS_RULE_UNKNOWN", CheckStatus.UNKNOWN, null, "后半程计值规则尚未明确。", ref);
            return result;
        }
        if (!rule.enabled()) {
            Collections.fill(result, false);
            add(checks, "BONUS_DISABLED", CheckStatus.SATISFIED, null, "当前规则明确不启用后半程加成。", ref);
            return result;
        }
        if (rule.startsAtSeconds() == null || rule.startsAtSeconds().signum() < 0 || rule.multiplier() == null
                || rule.multiplier().signum() < 0 || rule.eligibleTypes() == null || rule.awardOrder() == null
                || (rule.lastElementsWindow() != null && rule.lastElementsWindow() < 0)
                || (rule.maximumAwards() != null && rule.maximumAwards() < 0)) {
            add(checks, "BONUS_RULE_INCOMPLETE", CheckStatus.UNKNOWN, null,
                    "后半程阈值、倍率、适用类型或奖励顺序不完整。", ref);
            return result;
        }
        Set<String> keys = normalizeKeys(rule.eligibleElementKeys());
        int windowStart = rule.lastElementsWindow() == null ? 0 : Math.max(0, elements.size() - rule.lastElementsWindow());
        for (int i = 0; i < elements.size(); i++) {
            Element element = elements.get(i);
            if (i < windowStart || (typeOf(element) != null && !rule.eligibleTypes().contains(typeOf(element)))
                    || (!keys.isEmpty() && !keys.contains(elementKey(element))) || Integer.valueOf(0).equals(rule.maximumAwards())) {
                result.set(i, false);
                continue;
            }
            BigDecimal time = timeOf(element);
            if (typeOf(element) == null || time == null || time.signum() < 0) continue;
            int comparison = time.compareTo(rule.startsAtSeconds());
            result.set(i, comparison > 0 || (comparison == 0 && rule.includeBoundary()));
        }
        if (rule.maximumAwards() != null) {
            int minimumUsed = 0;
            int maximumUsed = 0;
            int cap = rule.maximumAwards();
            for (int order = 0; order < elements.size(); order++) {
                int i = rule.awardOrder() == AwardOrder.FIRST ? order : elements.size() - 1 - order;
                Boolean qualifies = result.get(i);
                if (Boolean.FALSE.equals(qualifies)) continue;
                if (minimumUsed >= cap) result.set(i, false);
                else if (Boolean.TRUE.equals(qualifies) && maximumUsed < cap) result.set(i, true);
                else result.set(i, null);
                if (Boolean.TRUE.equals(qualifies)) minimumUsed = Math.min(cap, minimumUsed + 1);
                maximumUsed = Math.min(cap, maximumUsed + 1);
            }
        }
        for (int i = 0; i < result.size(); i++) {
            Boolean awarded = result.get(i);
            add(checks, "BACK_HALF_BONUS", awarded == null ? CheckStatus.UNKNOWN : CheckStatus.SATISFIED,
                    idOf(elements.get(i), i), awarded == null ? "时间、资格或奖励名额尚不能确定，未认定加成。" :
                    awarded ? "按当前时间、资格和名额适用后半程系数。" : "按当前规则不适用后半程系数。", ref);
        }
        return result;
    }

    private void checkSelection(Element element, String id, SelectionPolicy selection, List<CheckResult> checks) {
        if (selection == null) {
            add(checks, "SELECTION_UNKNOWN", CheckStatus.UNKNOWN, id, "尚未提供本次可选动作范围。", "athlete-selection");
            return;
        }
        Set<String> excluded = normalizeCodes(selection.globallyExcludedJumps());
        for (String jump : safeJumps(element)) if (excluded.contains(normalizeJumpCode(jump)))
            add(checks, "GLOBAL_JUMP_EXCLUSION", CheckStatus.VIOLATED, id,
                    "该行包含本次明确全局排除的动作 " + normalizeJumpCode(jump) + "。", "athlete-selection");
        Set<String> selected = switch (typeOf(element) == null ? "UNKNOWN" : typeOf(element).name()) {
            case "SINGLE" -> selection.selectedSingles();
            case "COMBINATION" -> selection.selectedCombinations();
            case "SEQUENCE" -> selection.selectedSequences();
            default -> null;
        };
        if (selected == null) add(checks, "SELECTION_UNKNOWN", CheckStatus.UNKNOWN, id,
                "该类型的本次可选范围尚未提供。", "athlete-selection");
        else {
            boolean allowed = normalizeKeys(selected).contains(elementKey(element));
            add(checks, "SELECTED_CANDIDATE", allowed ? CheckStatus.SATISFIED : CheckStatus.VIOLATED, id,
                    allowed ? "该完整动作候选在本次选择范围内。" : "该完整动作候选未在本次选择范围内。", "athlete-selection");
        }
    }

    private void checkLimits(List<Element> elements, Limits limits, List<CheckResult> checks, String ref) {
        if (limits == null) {
            add(checks, "LIMITS_UNKNOWN", CheckStatus.UNKNOWN, null, "元素数量、连跳及重复条件尚未全部表达。", ref);
            return;
        }
        checkCount("ELEMENT_COUNT", elements.size(), limits.elements(), null, checks, ref);
        checkCount("COMBINATION_COUNT", (int) elements.stream().filter(e -> typeOf(e) == ElementType.COMBINATION).count(),
                limits.combinations(), null, checks, ref);
        checkCount("SEQUENCE_COUNT", (int) elements.stream().filter(e -> typeOf(e) == ElementType.SEQUENCE).count(),
                limits.sequences(), null, checks, ref);
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        for (int i = 0; i < elements.size(); i++) {
            Element element = elements.get(i);
            checkCount("JUMPS_PER_ELEMENT", safeJumps(element).size(), limits.jumpsPerElement(), idOf(element, i), checks, ref);
            for (String code : safeJumps(element)) occurrences.merge(normalizeJumpCode(code), 1, Integer::sum);
        }
        Map<String, Integer> maxima = new HashMap<>();
        Set<String> conflictingMaxima = new HashSet<>();
        if (limits.jumpOccurrences() != null) limits.jumpOccurrences().forEach((key, value) -> {
            String normalized = normalizeJumpCode(key);
            if (maxima.containsKey(normalized) && !Objects.equals(maxima.get(normalized), value)) {
                conflictingMaxima.add(normalized);
                add(checks, "REPETITION_RULE_CONFLICT", CheckStatus.UNKNOWN, null,
                        "动作 " + normalized + " 的别名对应相互冲突的重复上限。", ref);
            } else maxima.put(normalized, value);
        });
        conflictingMaxima.forEach(maxima::remove);
        for (Map.Entry<String, Integer> maximum : maxima.entrySet()) {
            if (maximum.getValue() == null || maximum.getValue() < 0) add(checks, "REPETITION_RULE_UNKNOWN", CheckStatus.UNKNOWN,
                    null, "动作 " + maximum.getKey() + " 的重复条件无有效解释。", ref);
            else {
                int actual = occurrences.getOrDefault(maximum.getKey(), 0);
                add(checks, "JUMP_REPETITION", actual <= maximum.getValue() ? CheckStatus.SATISFIED : CheckStatus.VIOLATED,
                        null, maximum.getKey() + " 出现 " + actual + " 次，已提供的上限为 " + maximum.getValue() + "。", ref);
            }
        }
        if (!limits.jumpOccurrencesComplete()) add(checks, "REPETITION_COVERAGE_UNKNOWN", CheckStatus.UNKNOWN, null,
                "只核对了已提供的动作重复条件，其他重复及组合关系仍待解释。", ref);
        if (limits.requiredJumps() == null) add(checks, "REQUIRED_JUMPS_UNKNOWN", CheckStatus.UNKNOWN, null,
                "必需动作条件尚未明确。", ref);
        else for (String required : limits.requiredJumps()) {
            String code = normalizeJumpCode(required);
            boolean present = occurrences.containsKey(code);
            add(checks, "REQUIRED_JUMP", present ? CheckStatus.SATISFIED : CheckStatus.VIOLATED, null,
                    present ? "包含明确要求的动作 " + code + "。" : "缺少明确要求的动作 " + code + "。", ref);
        }
    }

    private void checkCount(String code, int count, CountLimit limit, String id, List<CheckResult> checks, String ref) {
        if (limit == null || (!limit.unrestricted() && (limit.maximum() == null || limit.maximum() < 0))
                || (limit.unrestricted() && limit.maximum() != null)) {
            add(checks, code, CheckStatus.UNKNOWN, id, "该数量条件尚未明确或存在矛盾。", ref);
        } else if (limit.unrestricted()) add(checks, code, CheckStatus.SATISFIED, id, "当前规则明确不限制此数量。", ref);
        else add(checks, code, count <= limit.maximum() ? CheckStatus.SATISFIED : CheckStatus.VIOLATED, id,
                "当前数量 " + count + "，已提供上限 " + limit.maximum() + "。", ref);
    }

    private Map<String, BigDecimal> canonicalValues(RuleSet rules, List<CheckResult> checks) {
        Map<String, BigDecimal> result = new HashMap<>();
        if (rules == null || rules.baseValues() == null) return result;
        Set<String> conflicts = new HashSet<>();
        rules.baseValues().forEach((rawCode, value) -> {
            String code = normalizeJumpCode(rawCode);
            if (code.isBlank() || value == null || value.signum() < 0) {
                conflicts.add(code);
                add(checks, "BASE_VALUE_INVALID", CheckStatus.UNKNOWN, null, "规则中存在未明确的动作基础分。", rules.sourceReference());
            } else if (result.containsKey(code) && result.get(code).compareTo(value) != 0) {
                conflicts.add(code);
                add(checks, "BASE_VALUE_CONFLICT", CheckStatus.UNKNOWN, null,
                        "动作 " + code + " 的别名对应不同基础分，需修正规则。", rules.sourceReference());
            } else result.put(code, value);
        });
        conflicts.forEach(result::remove);
        return result;
    }

    private static Set<String> normalizeCodes(Set<String> codes) {
        Set<String> result = new HashSet<>();
        if (codes != null) codes.forEach(code -> result.add(normalizeJumpCode(code)));
        return result;
    }

    private static Set<String> normalizeKeys(Set<String> keys) {
        Set<String> result = new HashSet<>();
        if (keys != null) for (String key : keys) {
            if (key == null) continue;
            result.add(String.join("+", List.of(key.split("\\+", -1)).stream().map(ScoringService::normalizeJumpCode).toList()));
        }
        return result;
    }

    private static Map<String, Integer> positions(List<Element> elements) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < elements.size(); i++) result.putIfAbsent(idOf(elements.get(i), i), i);
        return result;
    }

    private static boolean sameElement(Element left, Element right) {
        return left != null && right != null && Objects.equals(left.id(), right.id())
                && left.type() == right.type() && elementKey(left).equals(elementKey(right))
                && sameNumber(left.plannedStartSeconds(), right.plannedStartSeconds());
    }

    private static boolean sameNumber(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static Integer oneBased(Integer value) { return value == null ? null : value + 1; }
    private static ElementType typeOf(Element element) { return element == null ? null : element.type(); }
    private static BigDecimal timeOf(Element element) { return element == null ? null : element.plannedStartSeconds(); }
    private static String idOf(Element element, int index) { return element == null || blank(element.id()) ? "position-" + (index + 1) : element.id(); }
    private static List<String> safeJumps(Element element) { return element == null ? List.of() : safe(element.jumps()); }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void add(List<CheckResult> checks, String code, CheckStatus status, String id, String message, String ref) {
        checks.add(new CheckResult(code, status, id, message, ref));
    }
}
