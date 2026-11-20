-- Q1：使用方案冻结画像中的统计筛选候选，不把 GOE 等级直接加到基础分。
SELECT p.id AS plan_id, e.code, st.level_code, st.attempt_count,
       st.success_rate, st.avg_goe AS avg_goe_grade, v.base_value
FROM program_plan p
JOIN capability_snapshot_stat cs ON cs.snapshot_id = p.capability_snapshot_id
JOIN element_skill_stat st ON st.id = cs.stat_id
JOIN element e ON e.id = st.element_id
JOIN element_level_value v ON v.element_id = st.element_id
    AND v.level_code = st.level_code AND v.ruleset_version_id = p.ruleset_version_id
WHERE p.id = 101 AND st.attempt_count >= 10 AND st.success_rate >= 0.70
ORDER BY st.success_rate DESC;

-- Q2：读取已经由工具计算的方案明细和总分；合成草稿没有评分，返回零行是预期结果。
SELECT i.seq_no, i.combo_index, e.code, i.level_code, i.planned_base_value,
       i.est_goe, i.est_success_prob, i.est_score
FROM program_element_item i JOIN element e ON e.id = i.element_id
WHERE i.plan_id = 101 AND i.is_alternate = FALSE
ORDER BY i.seq_no, i.combo_index;
SELECT tes_expected, pcs_components, pcs_expected, deduction_expected, total_expected, engine_version
FROM program_score_breakdown WHERE plan_id = 101;

-- Q3：对比方案，同时呈现未检查与警告，不能将无违规行等同于合法。
SELECT p.id, p.version_no, p.status, b.total_expected,
       COUNT(c.id) AS check_count,
       COUNT(c.id) FILTER (WHERE c.status = 'violation') AS violations,
       COUNT(c.id) FILTER (WHERE c.status = 'warn') AS warnings
FROM program_plan p
LEFT JOIN program_score_breakdown b ON b.plan_id = p.id
LEFT JOIN program_constraint_check c ON c.plan_id = p.id
WHERE p.skater_id = 42
GROUP BY p.id, b.total_expected
ORDER BY p.version_no;

-- Q4：某视频经哪条消息进入会话，又通过哪条证据影响方案或画像。
WITH adopted AS (
    SELECT plan_id, evidence_id, 'plan' AS adopted_path, influence_summary
    FROM agent_plan_evidence
    UNION ALL
    SELECT p.id, se.evidence_id, 'snapshot', se.influence_summary
    FROM program_plan p
    JOIN capability_snapshot_evidence se ON se.snapshot_id = p.capability_snapshot_id
)
SELECT a.id AS asset_id, sa.session_id AS input_session_id, sa.message_id,
       e.id AS evidence_id, e.t_start, e.t_end, e.claim, e.review_status,
       ap.plan_id, p.agent_session_id AS plan_session_id, ap.adopted_path, ap.influence_summary
FROM asset a
JOIN agent_session_asset sa ON sa.asset_id = a.id
JOIN agent_evidence e ON e.session_asset_id = sa.id AND e.session_id = sa.session_id
JOIN adopted ap ON ap.evidence_id = e.id
JOIN program_plan p ON p.id = ap.plan_id
WHERE a.id = 9001
ORDER BY ap.plan_id, e.id, ap.adopted_path;

-- Q5：读取某方案实际绑定的规则，包含草稿状态，不偷换成当前 active 版本。
SELECT p.id AS plan_id, rv.season_code, rv.revision_no, rv.status,
       pt.code, w.skater_category, w.rule_key, w.constraint_kind,
       w.min_count, w.max_count, w.penalty_note
FROM program_plan p
JOIN ruleset_version rv ON rv.id = p.ruleset_version_id
JOIN well_balanced_rule w ON w.ruleset_version_id = rv.id AND w.program_type_id = p.program_type_id
JOIN program_type pt ON pt.id = w.program_type_id
WHERE p.id = 101
ORDER BY w.rule_key;

-- Q6：原始试做的近九十天趋势；与方案实际使用的冻结统计是不同查询目的。
SELECT date_trunc('month', l.attempted_at) AS month, e.code,
       COUNT(*) AS attempts,
       ROUND(AVG(CASE WHEN l.is_success THEN 1.0 ELSE 0.0 END), 3) AS success_rate,
       ROUND(AVG(l.goe), 2) AS avg_goe_grade
FROM element_attempt_log l JOIN element e ON e.id = l.element_id
WHERE l.skater_id = 42 AND l.attempted_at >= now() - INTERVAL '90 days'
GROUP BY 1, 2 ORDER BY 1, 2;

-- Q7：方案的完整会话工具与模型记录；分别聚合，避免多对多展开造成重复计费。
SELECT p.id AS plan_id, s.id AS session_id, s.config_snapshot,
       s.input_snapshot, s.cost_used, s.currency,
       (SELECT jsonb_agg(jsonb_build_object(
           'id', t.id, 'tool', t.tool_name, 'version', t.tool_version,
           'attempt', t.attempt_no, 'status', t.status, 'input', t.input_payload,
           'output', t.output_payload) ORDER BY t.id)
        FROM agent_tool_call t WHERE t.session_id = s.id) AS tool_calls,
       (SELECT jsonb_agg(jsonb_build_object(
           'id', o.id, 'role', o.agent_role, 'model_alias', o.model_alias,
           'deployment_revision', o.deployment_revision, 'prompt_version', o.prompt_version,
           'validation_status', o.validation_status, 'output', o.structured_output,
           'usage', o.usage) ORDER BY o.id)
        FROM agent_llm_output o WHERE o.session_id = s.id) AS model_outputs
FROM program_plan p JOIN agent_session s ON s.id = p.agent_session_id
WHERE p.id = 101;

-- Q8：只追溯实际采用的证据及其生产调用、模型输出和人工复核。
SELECT pe.plan_id, pe.usage_kind, pe.influence_summary, e.id AS evidence_id,
       e.claim, e.review_status, t.id AS tool_call_id, t.tool_name,
       o.id AS llm_output_id, o.structured_output,
       r.reviewer_user_id, r.decision, r.reason
FROM agent_plan_evidence pe
JOIN agent_evidence e ON e.id = pe.evidence_id AND e.session_id = pe.session_id
LEFT JOIN agent_tool_call t ON t.id = e.tool_call_id AND t.session_id = e.session_id
LEFT JOIN agent_llm_output o ON o.id = e.llm_output_id AND o.session_id = e.session_id
LEFT JOIN agent_review r ON r.evidence_id = e.id AND r.session_id = e.session_id
WHERE pe.plan_id = 101 ORDER BY e.id, r.created_at;
