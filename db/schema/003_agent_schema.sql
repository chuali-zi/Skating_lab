-- 智能体扩展：在既有 8 域 32 表上新增 12 表，总计 9 域 44 表。
-- 顺序：001_schema.sql、002_seed_minimal.sql、003_agent_schema.sql。
-- 空库初始化快照，非已部署数据库的增量迁移；业务门禁见技术设计。
BEGIN;

-- 域 C：正式规则原文与 RAG 来源；结构化数值映射由 structured_refs 留存。
CREATE TABLE rule_source_chunk (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ruleset_version_id BIGINT NOT NULL REFERENCES ruleset_version (id),
    chunk_key TEXT NOT NULL,
    source_url TEXT NOT NULL,
    source_locator TEXT NOT NULL,
    content TEXT NOT NULL,
    content_sha256 CHAR(64) NOT NULL CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    applicability JSONB NOT NULL DEFAULT '{}'::jsonb,
    structured_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    review_status TEXT NOT NULL DEFAULT 'pending' CHECK (review_status IN ('pending', 'confirmed', 'rejected')),
    reviewed_by BIGINT REFERENCES app_user (id),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (ruleset_version_id, chunk_key),
    UNIQUE (id, ruleset_version_id),
    CHECK (review_status <> 'confirmed' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
COMMENT ON TABLE rule_source_chunk IS '库存规则原文：页码或条款定位、版本、哈希、适用范围及复核状态';
COMMENT ON COLUMN rule_source_chunk.structured_refs IS '结构化行映射数组，含 table_name、row_id、field_name；应用校验允许表与同一规则版本';

ALTER TABLE capability_snapshot ADD CONSTRAINT uq_snapshot_skater UNIQUE (id, skater_id);

-- 域 I：智能体会话、消息、工具、输出、轨迹、证据、采用关系与复核。
CREATE TABLE agent_session (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skater_id BIGINT NOT NULL REFERENCES skater_profile (id),
    created_by BIGINT NOT NULL REFERENCES app_user (id),
    competition_category_id BIGINT REFERENCES competition_category (id),
    program_type_id BIGINT NOT NULL REFERENCES program_type (id),
    ruleset_version_id BIGINT NOT NULL REFERENCES ruleset_version (id),
    capability_snapshot_id BIGINT,
    status TEXT NOT NULL DEFAULT 'created' CHECK (status IN (
        'created', 'understanding', 'awaiting_confirmation', 'planning', 'validating',
        'awaiting_finalization', 'completed', 'failed', 'cancelled')),
    revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0),
    goal JSONB NOT NULL,
    input_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    config_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    budget_limit NUMERIC(12,6) NOT NULL CHECK (budget_limit >= 0),
    cost_used NUMERIC(12,6) NOT NULL DEFAULT 0 CHECK (cost_used >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    error_code TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (capability_snapshot_id, skater_id) REFERENCES capability_snapshot (id, skater_id),
    UNIQUE (id, skater_id, ruleset_version_id, program_type_id, capability_snapshot_id)
);
COMMENT ON TABLE agent_session IS '会话与持久状态；确认后冻结画像及输入，revision 用于应用乐观锁';
COMMENT ON COLUMN agent_session.config_snapshot IS '脱敏配置、提示和工具版本、部署修订摘要、采样与预算设置；不存密钥';
CREATE INDEX ix_agent_session_skater ON agent_session (skater_id, created_at DESC);
CREATE INDEX ix_agent_session_queue ON agent_session (status, updated_at)
    WHERE status NOT IN ('completed', 'failed', 'cancelled');
CREATE TRIGGER trg_agent_session_updated BEFORE UPDATE ON agent_session
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

ALTER TABLE program_plan ADD COLUMN agent_session_id BIGINT;
ALTER TABLE program_plan ADD CONSTRAINT uq_plan_session UNIQUE (id, agent_session_id);
ALTER TABLE program_plan ADD CONSTRAINT fk_plan_session FOREIGN KEY (agent_session_id)
    REFERENCES agent_session (id);
ALTER TABLE program_plan ADD CONSTRAINT fk_plan_session_context FOREIGN KEY
    (agent_session_id, skater_id, ruleset_version_id, program_type_id, capability_snapshot_id)
    REFERENCES agent_session (id, skater_id, ruleset_version_id, program_type_id, capability_snapshot_id);
ALTER TABLE program_plan ADD CONSTRAINT ck_plan_agent_context CHECK (
    (origin <> 'agent' OR agent_session_id IS NOT NULL)
    AND (agent_session_id IS NULL OR capability_snapshot_id IS NOT NULL));
CREATE INDEX ix_plan_agent_session ON program_plan (agent_session_id);
COMMENT ON COLUMN program_plan.agent_session_id IS '当前方案生成会话；复合外键匹配选手、规则、节目及冻结画像';

CREATE TABLE agent_message (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES agent_session (id),
    seq_no INTEGER NOT NULL CHECK (seq_no > 0),
    role TEXT NOT NULL CHECK (role IN ('user', 'assistant', 'system', 'tool')),
    author_user_id BIGINT REFERENCES app_user (id),
    content JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, seq_no),
    UNIQUE (id, session_id)
);
COMMENT ON TABLE agent_message IS '会话消息：按会话序号追加；结构化正文仅保存必要且已脱敏内容';

CREATE TABLE agent_session_asset (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES agent_session (id),
    message_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL REFERENCES asset (id),
    consent_scope TEXT NOT NULL,
    consented_by BIGINT NOT NULL REFERENCES app_user (id),
    consented_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (message_id, session_id) REFERENCES agent_message (id, session_id),
    UNIQUE (session_id, message_id, asset_id),
    UNIQUE (id, session_id),
    CHECK (revoked_at IS NULL OR revoked_at >= consented_at)
);
COMMENT ON TABLE agent_session_asset IS '哪条消息将哪份资料带入本次会话；授权范围与撤回记录由应用执行';
CREATE INDEX ix_session_asset_source ON agent_session_asset (asset_id, session_id);

CREATE TABLE agent_tool_call (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES agent_session (id),
    parent_call_id BIGINT,
    tool_name TEXT NOT NULL,
    tool_version TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    attempt_no INTEGER NOT NULL DEFAULT 1 CHECK (attempt_no > 0),
    status TEXT NOT NULL CHECK (status IN ('pending', 'running', 'succeeded', 'failed', 'cancelled')),
    input_payload JSONB NOT NULL,
    output_payload JSONB,
    is_cache_hit BOOLEAN NOT NULL DEFAULT FALSE,
    error_code TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, idempotency_key, attempt_no),
    UNIQUE (id, session_id),
    FOREIGN KEY (parent_call_id, session_id) REFERENCES agent_tool_call (id, session_id),
    CHECK (parent_call_id IS NULL OR parent_call_id <> id),
    CHECK (finished_at IS NULL OR (started_at IS NOT NULL AND finished_at >= started_at)),
    CHECK (status <> 'succeeded' OR (output_payload IS NOT NULL AND finished_at IS NOT NULL))
);
COMMENT ON TABLE agent_tool_call IS '工具调用与重试：同一逻辑调用按 attempt_no 区分；缓存命中仍保留调用记录';
CREATE INDEX ix_tool_session_time ON agent_tool_call (session_id, created_at);

CREATE TABLE agent_llm_output (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES agent_session (id),
    tool_call_id BIGINT,
    agent_role TEXT NOT NULL,
    model_alias TEXT NOT NULL,
    adapter_version TEXT NOT NULL,
    deployment_revision TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    request_sha256 CHAR(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    generation_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    structured_output JSONB NOT NULL,
    validation_status TEXT NOT NULL CHECK (validation_status IN ('pending', 'valid', 'invalid')),
    usage JSONB NOT NULL DEFAULT '{}'::jsonb,
    cost_amount NUMERIC(12,6) CHECK (cost_amount IS NULL OR cost_amount >= 0),
    currency CHAR(3) NOT NULL DEFAULT 'CNY',
    latency_ms INTEGER CHECK (latency_ms IS NULL OR latency_ms >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, session_id),
    FOREIGN KEY (tool_call_id, session_id) REFERENCES agent_tool_call (id, session_id)
);
COMMENT ON TABLE agent_llm_output IS '模型可见输出及使用元信息；部署别名由环境注入，不绑定具体厂商或模型 ID';
COMMENT ON COLUMN agent_llm_output.usage IS '文本 token 与媒体计量分别记录，缓存和错误用量也纳入会话预算';
CREATE INDEX ix_llm_session_time ON agent_llm_output (session_id, created_at);

CREATE TABLE agent_trace (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES agent_session (id),
    seq_no INTEGER NOT NULL CHECK (seq_no > 0),
    event_type TEXT NOT NULL,
    decision_summary TEXT NOT NULL,
    tool_call_id BIGINT,
    llm_output_id BIGINT,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, seq_no),
    FOREIGN KEY (tool_call_id, session_id) REFERENCES agent_tool_call (id, session_id),
    FOREIGN KEY (llm_output_id, session_id) REFERENCES agent_llm_output (id, session_id)
);
COMMENT ON TABLE agent_trace IS '可审计事件与决策摘要，记录理由和引用；不采集模型隐藏思维链';

CREATE TABLE agent_evidence (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES agent_session (id),
    session_asset_id BIGINT,
    message_id BIGINT,
    rule_source_chunk_id BIGINT REFERENCES rule_source_chunk (id),
    capability_evidence_id BIGINT REFERENCES capability_evidence (id),
    tool_call_id BIGINT,
    llm_output_id BIGINT,
    supersedes_evidence_id BIGINT,
    claim TEXT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    t_start NUMERIC(10,3),
    t_end NUMERIC(10,3),
    confidence NUMERIC(4,3) CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1),
    review_status TEXT NOT NULL DEFAULT 'pending' CHECK (review_status IN ('pending', 'confirmed', 'rejected')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, session_id),
    FOREIGN KEY (session_asset_id, session_id) REFERENCES agent_session_asset (id, session_id),
    FOREIGN KEY (message_id, session_id) REFERENCES agent_message (id, session_id),
    FOREIGN KEY (tool_call_id, session_id) REFERENCES agent_tool_call (id, session_id),
    FOREIGN KEY (llm_output_id, session_id) REFERENCES agent_llm_output (id, session_id),
    FOREIGN KEY (supersedes_evidence_id, session_id) REFERENCES agent_evidence (id, session_id),
    CHECK (supersedes_evidence_id IS NULL OR supersedes_evidence_id <> id),
    CHECK (num_nonnulls(session_asset_id, message_id, rule_source_chunk_id, capability_evidence_id) = 1),
    CHECK ((t_start IS NULL AND t_end IS NULL) OR
           (session_asset_id IS NOT NULL AND t_start IS NOT NULL AND t_end IS NOT NULL
            AND t_start >= 0 AND t_end >= t_start))
);
COMMENT ON TABLE agent_evidence IS '候选证据：四类来源恰好一种，生产工具与模型输出单独关联；确认须有人工复核事务';
CREATE INDEX ix_agent_evidence_session ON agent_evidence (session_id, review_status);
CREATE INDEX ix_agent_evidence_asset ON agent_evidence (session_asset_id);

-- 域 E：冻结画像实际采用的证据与统计，不用最新记录推测历史使用关系。
CREATE TABLE capability_snapshot_evidence (
    snapshot_id BIGINT NOT NULL REFERENCES capability_snapshot (id),
    evidence_id BIGINT NOT NULL REFERENCES agent_evidence (id),
    influence_summary TEXT NOT NULL,
    PRIMARY KEY (snapshot_id, evidence_id)
);
COMMENT ON TABLE capability_snapshot_evidence IS '画像使用的已确认观察；同选手及确认状态由画像构建事务检查';
CREATE INDEX ix_snapshot_evidence_source ON capability_snapshot_evidence (evidence_id);

CREATE TABLE capability_snapshot_stat (
    snapshot_id BIGINT NOT NULL REFERENCES capability_snapshot (id),
    stat_id BIGINT NOT NULL REFERENCES element_skill_stat (id),
    PRIMARY KEY (snapshot_id, stat_id)
);
COMMENT ON TABLE capability_snapshot_stat IS '画像使用的确切统计快照；同选手与去重窗口由画像构建事务检查';
CREATE INDEX ix_snapshot_stat_source ON capability_snapshot_stat (stat_id);

CREATE TABLE agent_plan_evidence (
    plan_id BIGINT NOT NULL,
    session_id BIGINT NOT NULL REFERENCES agent_session (id),
    evidence_id BIGINT NOT NULL,
    usage_kind TEXT NOT NULL CHECK (usage_kind IN ('capability', 'rule', 'preference', 'restriction', 'explanation')),
    influence_summary TEXT NOT NULL,
    PRIMARY KEY (plan_id, evidence_id, usage_kind),
    FOREIGN KEY (plan_id, session_id) REFERENCES program_plan (id, agent_session_id),
    FOREIGN KEY (evidence_id, session_id) REFERENCES agent_evidence (id, session_id)
);
COMMENT ON TABLE agent_plan_evidence IS '方案实际采用的证据与影响摘要；复合外键阻止跨会话错挂';
CREATE INDEX ix_plan_evidence_source ON agent_plan_evidence (evidence_id, plan_id);

CREATE TABLE agent_review (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    session_id BIGINT NOT NULL REFERENCES agent_session (id),
    evidence_id BIGINT,
    plan_id BIGINT,
    reviewer_user_id BIGINT NOT NULL REFERENCES app_user (id),
    decision TEXT NOT NULL CHECK (decision IN ('confirmed', 'rejected', 'changes_requested')),
    reason TEXT NOT NULL,
    proposed_changes JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (num_nonnulls(evidence_id, plan_id) = 1),
    FOREIGN KEY (evidence_id, session_id) REFERENCES agent_evidence (id, session_id),
    FOREIGN KEY (plan_id, session_id) REFERENCES program_plan (id, agent_session_id)
);
COMMENT ON TABLE agent_review IS '人工复核日志：证据或方案二选一；修改建议不等于确认，权限与版本由事务校验';
CREATE INDEX ix_review_session ON agent_review (session_id, created_at);

COMMIT;
