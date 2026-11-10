-- 可选合成演示数据：展示血缘查询，不是实际模型调用、评分或合法节目。
-- 仅在 001 至 003 完成的空白开发库执行一次。保留 draft 规则与 draft 方案。
BEGIN;
DO $$
DECLARE
    demo_user BIGINT;
    demo_skater BIGINT;
    demo_asset BIGINT;
    demo_session BIGINT;
    demo_message BIGINT;
    demo_session_asset BIGINT;
    demo_tool BIGINT;
    demo_output BIGINT;
    demo_evidence BIGINT;
    demo_capability BIGINT;
    demo_snapshot BIGINT;
    demo_stat BIGINT;
    demo_plan BIGINT;
BEGIN
    INSERT INTO app_user (username, password_hash, display_name, role_id, status)
    VALUES ('demo_coach', '!disabled-demo', '合成教练', 2, 'disabled') RETURNING id INTO demo_user;
    INSERT INTO skater_profile (id, full_name, gender, current_category) OVERRIDING SYSTEM VALUE
    VALUES (42, '合成选手', 'X', 'senior') RETURNING id INTO demo_skater;
    INSERT INTO asset (id, skater_id, asset_type, storage_key, original_filename,
                      duration_sec, uploaded_by, meta) OVERRIDING SYSTEM VALUE
    VALUES (9001, demo_skater, 'video', 'demo/training-placeholder', 'training-placeholder.mp4',
            30, demo_user, '{"synthetic":true,"file_available":false}') RETURNING id INTO demo_asset;
    INSERT INTO agent_session (skater_id, created_by, program_type_id, ruleset_version_id,
                               goal, budget_limit, status, config_snapshot)
    VALUES (demo_skater, demo_user, 2, 1, '{"goal":"保留后半程体能","synthetic":true}',
            1, 'awaiting_finalization', '{"mode":"synthetic_fixture"}') RETURNING id INTO demo_session;
    INSERT INTO agent_message (session_id, seq_no, role, author_user_id, content)
    VALUES (demo_session, 1, 'user', demo_user, '{"text":"后半程容易疲劳，请先确认再编排。","synthetic":true}')
    RETURNING id INTO demo_message;
    INSERT INTO agent_session_asset (session_id, message_id, asset_id, consent_scope, consented_by, consented_at)
    VALUES (demo_session, demo_message, demo_asset, '合成资料，仅供本地血缘演示', demo_user, now())
    RETURNING id INTO demo_session_asset;
    INSERT INTO agent_tool_call (session_id, tool_name, tool_version, idempotency_key, status,
                                 input_payload, output_payload, started_at, finished_at)
    VALUES (demo_session, 'multimodal_observe', 'interface_v1', 'demo_observe', 'succeeded',
            jsonb_build_object('asset_id', demo_asset, 'mode', 'synthetic_fixture'),
            '{"observation":"待复核的后半程稳定性线索","synthetic":true}', now(), now())
    RETURNING id INTO demo_tool;
    INSERT INTO agent_llm_output (session_id, tool_call_id, agent_role, model_alias, adapter_version,
                                  deployment_revision, prompt_version, request_sha256, structured_output,
                                  validation_status, usage, cost_amount, latency_ms)
    VALUES (demo_session, demo_tool, 'multimodal_understanding', 'vision_role', 'interface_v1',
            'synthetic_fixture', 'observation_v1', repeat('0',64),
            '{"observations":["需教练核实后半程稳定性"],"synthetic":true}',
            'valid', '{"synthetic":true,"text_tokens":0,"media_units":0}', 0, 0)
    RETURNING id INTO demo_output;
    INSERT INTO agent_evidence (session_id, session_asset_id, tool_call_id, llm_output_id,
                                claim, t_start, t_end, review_status, payload)
    VALUES (demo_session, demo_session_asset, demo_tool, demo_output,
            '教练确认：近期后半程训练需保守安排；合成结论', 12, 18, 'confirmed', '{"synthetic":true}')
    RETURNING id INTO demo_evidence;
    INSERT INTO agent_review (session_id, evidence_id, reviewer_user_id, decision, reason)
    VALUES (demo_session, demo_evidence, demo_user, 'confirmed', '合成复核记录，用于展示确认链路');
    INSERT INTO capability_evidence (skater_id, source_type, source_id, metric_key, captured_at)
    VALUES (demo_skater, 'asset', demo_asset, 'late_program_stability_note', now()) RETURNING id INTO demo_capability;
    INSERT INTO capability_snapshot (skater_id, risk_tags, source_summary)
    VALUES (demo_skater, ARRAY['conservative_late_program'],
            jsonb_build_object('synthetic', true, 'capability_evidence_ids', jsonb_build_array(demo_capability)))
    RETURNING id INTO demo_snapshot;
    INSERT INTO element_skill_stat (skater_id, element_id, level_code, stat_window, attempt_count, success_rate, avg_goe)
    VALUES (demo_skater, 3, 'N', 'recent_90d', 10, 0.8, 1.0) RETURNING id INTO demo_stat;
    INSERT INTO capability_snapshot_evidence (snapshot_id, evidence_id, influence_summary)
    VALUES (demo_snapshot, demo_evidence, '限制后半程冒进；为合成演示关系');
    INSERT INTO capability_snapshot_stat (snapshot_id, stat_id) VALUES (demo_snapshot, demo_stat);
    UPDATE agent_session SET capability_snapshot_id = demo_snapshot,
        input_snapshot = '{"mode":"synthetic_fixture","rules_verified":false}', revision = 1
    WHERE id = demo_session;
    INSERT INTO program_plan (id, skater_id, program_type_id, ruleset_version_id, capability_snapshot_id,
                              agent_session_id, origin, status, created_by, notes) OVERRIDING SYSTEM VALUE
    VALUES (101, demo_skater, 2, 1, demo_snapshot, demo_session, 'agent', 'draft', demo_user,
            '合成草稿：只有血缘示例，不是完整节目，未求解、未评分、不可定稿') RETURNING id INTO demo_plan;
    INSERT INTO agent_plan_evidence (plan_id, session_id, evidence_id, usage_kind, influence_summary)
    VALUES (demo_plan, demo_session, demo_evidence, 'restriction', '将后半程保稳列为方案意图，尚未完成校验');
    INSERT INTO program_constraint_check (plan_id, check_key, status, detail)
    VALUES (demo_plan, 'coverage', 'warn', '{"complete":false,"reason":"规则为示意草稿，未运行校验"}');
    INSERT INTO agent_trace (session_id, seq_no, event_type, decision_summary, tool_call_id, llm_output_id)
    VALUES (demo_session, 1, 'observation_recorded', '合成观察已记录并人工确认，可追溯到视频片段', demo_tool, demo_output);
    INSERT INTO agent_trace (session_id, seq_no, event_type, decision_summary, payload)
    VALUES (demo_session, 2, 'draft_created', '仅演示证据采用关系，未运行完整规划', jsonb_build_object('plan_id', demo_plan));
    INSERT INTO agent_review (session_id, plan_id, reviewer_user_id, decision, reason)
    VALUES (demo_session, demo_plan, demo_user, 'changes_requested', '示意规则未发布且方案不完整，禁止定稿');
END;
$$;
SELECT setval(pg_get_serial_sequence('skater_profile', 'id'), (SELECT MAX(id) FROM skater_profile));
SELECT setval(pg_get_serial_sequence('asset', 'id'), (SELECT MAX(id) FROM asset));
SELECT setval(pg_get_serial_sequence('program_plan', 'id'), (SELECT MAX(id) FROM program_plan));
COMMIT;
