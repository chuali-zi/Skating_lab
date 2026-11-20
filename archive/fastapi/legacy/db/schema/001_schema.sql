-- ============================================================================
-- Skating Coach · PostgreSQL 16 基础结构（v0.2）
-- 既有 8 域 32 表；003_agent_schema.sql 扩展后为 9 域 44 表。
-- 执行方式：psql -d skating_coach -f db/schema/001_schema.sql
-- 注意：正式分值/数量限制以当季 ISU 官方通讯双人核对录入为准。
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 通用触发器：维护 updated_at
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- 域 A：身份与权限
-- ============================================================================

CREATE TABLE role (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        TEXT        NOT NULL,
    name_zh     TEXT        NOT NULL,
    description TEXT,
    is_system   BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_role_code UNIQUE (code)
);
COMMENT ON TABLE  role             IS '角色字典：admin/coach/skater/medic/viewer';
COMMENT ON COLUMN role.is_system   IS '预置系统角色，应用层禁止删除';

CREATE TABLE app_user (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    display_name  TEXT        NOT NULL,
    email         TEXT,
    phone         TEXT,
    role_id       BIGINT      NOT NULL,
    status        TEXT        NOT NULL DEFAULT 'active',
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_username UNIQUE (username),
    CONSTRAINT uq_app_user_email    UNIQUE (email),
    CONSTRAINT fk_app_user_role     FOREIGN KEY (role_id) REFERENCES role (id),
    CONSTRAINT ck_app_user_status   CHECK (status IN ('active', 'disabled'))
);
COMMENT ON TABLE app_user IS '应用用户：教练/队医/管理员等登录主体';

CREATE INDEX ix_app_user_role ON app_user (role_id);

CREATE TRIGGER trg_app_user_updated BEFORE UPDATE ON app_user
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ============================================================================
-- 域 C（前置部分）：元素字典与规则集 —— 供后续域引用
-- ============================================================================

CREATE TABLE element_category (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code      TEXT   NOT NULL,
    name_zh   TEXT   NOT NULL,
    sort_order      SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_element_category_code UNIQUE (code)
);
COMMENT ON TABLE element_category IS '元素类别：jump/spin/step/choreo';

CREATE TABLE element (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             TEXT        NOT NULL,
    name_en          TEXT        NOT NULL,
    name_zh          TEXT        NOT NULL,
    category_id      BIGINT      NOT NULL,
    jump_rotations   SMALLINT,
    has_flying       BOOLEAN     NOT NULL DEFAULT FALSE,
    has_change_foot  BOOLEAN     NOT NULL DEFAULT FALSE,
    is_combinable    BOOLEAN     NOT NULL DEFAULT TRUE,
    is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
    description      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_element_code UNIQUE (code),
    CONSTRAINT fk_element_category FOREIGN KEY (category_id) REFERENCES element_category (id),
    CONSTRAINT ck_element_rotations CHECK (jump_rotations IS NULL OR jump_rotations BETWEEN 1 AND 4)
);
COMMENT ON TABLE  element                   IS 'ISU 动作元素字典；跳跃含周数，旋转/步伐级别由 element_level_value 展开';
COMMENT ON COLUMN element.jump_rotations    IS '跳跃周数 1-4，仅跳跃类非空（跨表规则由触发器/应用层保证）';
COMMENT ON COLUMN element.is_combinable     IS '能否进入联合跳';

-- 跨表形态规则示例：jump 类必须提供 jump_rotations，非 jump 类必须为空
CREATE OR REPLACE FUNCTION check_element_shape() RETURNS trigger AS $$
DECLARE
    cat_code TEXT;
BEGIN
    SELECT code INTO cat_code FROM element_category WHERE id = NEW.category_id;
    IF cat_code = 'jump' AND NEW.jump_rotations IS NULL THEN
        RAISE EXCEPTION 'jump 元素 % 必须提供 jump_rotations', NEW.code;
    END IF;
    IF cat_code <> 'jump' AND NEW.jump_rotations IS NOT NULL THEN
        RAISE EXCEPTION '非 jump 元素 % 不允许设置 jump_rotations', NEW.code;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_element_shape BEFORE INSERT OR UPDATE ON element
    FOR EACH ROW EXECUTE FUNCTION check_element_shape();

CREATE TRIGGER trg_element_updated BEFORE UPDATE ON element
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE ruleset_version (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    season_code           TEXT        NOT NULL,
    revision_no           INTEGER     NOT NULL DEFAULT 1 CHECK (revision_no >= 1),
    discipline            TEXT        NOT NULL,
    name                  TEXT        NOT NULL,
    isu_communication_no  TEXT,
    effective_from        DATE        NOT NULL,
    effective_to          DATE,
    source_url            TEXT,
    status                TEXT        NOT NULL DEFAULT 'draft',
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ruleset_season_discipline UNIQUE (season_code, discipline, revision_no),
    CONSTRAINT ck_ruleset_discipline CHECK (discipline IN ('singles', 'pairs', 'ice_dance')),
    CONSTRAINT ck_ruleset_status     CHECK (status IN ('draft', 'active', 'retired')),
    CONSTRAINT ck_ruleset_period     CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
COMMENT ON TABLE ruleset_version IS '规则集版本：赛季×项目×修订；发布后由应用权限禁止原地改写';

-- 同一 discipline 至多一个 active 规则集
CREATE UNIQUE INDEX uq_ruleset_active ON ruleset_version (discipline) WHERE status = 'active';

CREATE TRIGGER trg_ruleset_updated BEFORE UPDATE ON ruleset_version
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE element_level_value (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ruleset_version_id BIGINT       NOT NULL,
    element_id         BIGINT       NOT NULL,
    level_code         TEXT         NOT NULL,
    base_value         NUMERIC(5,2) NOT NULL,
    CONSTRAINT uq_elv UNIQUE (ruleset_version_id, element_id, level_code),
    CONSTRAINT fk_elv_ruleset FOREIGN KEY (ruleset_version_id) REFERENCES ruleset_version (id),
    CONSTRAINT fk_elv_element FOREIGN KEY (element_id) REFERENCES element (id),
    CONSTRAINT ck_elv_level CHECK (level_code IN ('N', 'B', '1', '2', '3', '4')),
    CONSTRAINT ck_elv_bv    CHECK (base_value >= 0)
);
COMMENT ON TABLE  element_level_value IS '基础分定价表：元素×级别×赛季规则集；跳跃级别恒为 N';
COMMENT ON COLUMN element_level_value.level_code IS 'N=不分级(跳跃) B/1-4=旋转与步伐级别';

CREATE INDEX ix_elv_element ON element_level_value (element_id);
CREATE INDEX ix_elv_ruleset_level ON element_level_value (ruleset_version_id, level_code);

CREATE TABLE program_type (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code             TEXT        NOT NULL,
    name_zh          TEXT        NOT NULL,
    discipline       TEXT        NOT NULL DEFAULT 'singles',
    duration_min_sec SMALLINT,
    duration_max_sec SMALLINT,
    notes            TEXT,
    CONSTRAINT uq_program_type_code UNIQUE (code),
    CONSTRAINT ck_program_type_discipline CHECK (discipline IN ('singles', 'pairs', 'ice_dance')),
    CONSTRAINT ck_program_type_duration CHECK (
        duration_min_sec IS NULL OR duration_max_sec IS NULL OR duration_max_sec >= duration_min_sec)
);
COMMENT ON TABLE program_type IS '节目类型：SP 短节目 / FS 自由滑；细分组别时长以 ruleset_parameter 为准';

CREATE TABLE ruleset_parameter (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ruleset_version_id BIGINT        NOT NULL,
    program_type_id    BIGINT,
    param_key          TEXT          NOT NULL,
    param_value        NUMERIC(8,3)  NOT NULL,
    unit               TEXT,
    description        TEXT,
    CONSTRAINT uq_ruleset_param UNIQUE NULLS NOT DISTINCT (ruleset_version_id, program_type_id, param_key),
    CONSTRAINT fk_rp_ruleset FOREIGN KEY (ruleset_version_id) REFERENCES ruleset_version (id),
    CONSTRAINT fk_rp_program_type FOREIGN KEY (program_type_id) REFERENCES program_type (id)
);
COMMENT ON TABLE ruleset_parameter IS '规则参数：GOE 比例/PCS 因子/扣分/时长等，program_type_id 为空表示 SP/FS 通用';

CREATE TABLE well_balanced_rule (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ruleset_version_id BIGINT      NOT NULL,
    program_type_id    BIGINT      NOT NULL,
    skater_category    TEXT,
    rule_key           TEXT        NOT NULL,
    constraint_kind    TEXT        NOT NULL,
    target_category_id BIGINT,
    target_element_id  BIGINT,
    min_count          SMALLINT,
    max_count          SMALLINT,
    penalty_note       TEXT,
    notes              TEXT,
    CONSTRAINT uq_wbr UNIQUE NULLS NOT DISTINCT (ruleset_version_id, program_type_id, skater_category, rule_key),
    CONSTRAINT fk_wbr_ruleset FOREIGN KEY (ruleset_version_id) REFERENCES ruleset_version (id),
    CONSTRAINT fk_wbr_program_type FOREIGN KEY (program_type_id) REFERENCES program_type (id),
    CONSTRAINT fk_wbr_target_category FOREIGN KEY (target_category_id) REFERENCES element_category (id),
    CONSTRAINT fk_wbr_target_element FOREIGN KEY (target_element_id) REFERENCES element (id),
    CONSTRAINT ck_wbr_kind CHECK (constraint_kind IN ('count_min', 'count_max', 'required', 'forbidden', 'value')),
    CONSTRAINT ck_wbr_skater_category CHECK (
        skater_category IS NULL OR skater_category IN ('novice', 'junior', 'senior'))
);
COMMENT ON TABLE well_balanced_rule IS '结构化条款：由白名单规则编译器读取；未知条款必须报告未覆盖';
COMMENT ON COLUMN well_balanced_rule.rule_key IS '机器可读条款键：element_total/jump_element/combo_jump/triple_combo/same_jump_repeat/...';

CREATE INDEX ix_wbr_lookup ON well_balanced_rule (ruleset_version_id, program_type_id);

-- ============================================================================
-- 域 B：选手健康与训练
-- ============================================================================

CREATE TABLE skater_profile (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id            BIGINT,
    full_name          TEXT        NOT NULL,
    gender             TEXT        NOT NULL,
    birth_date         DATE,
    height_cm          NUMERIC(5,1),
    weight_kg          NUMERIC(5,1),
    dominant_side      TEXT,
    club_name          TEXT,
    current_category   TEXT,
    primary_discipline TEXT        NOT NULL DEFAULT 'ladies_singles',
    notes              TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_skater_user UNIQUE (user_id),
    CONSTRAINT fk_skater_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT ck_skater_gender CHECK (gender IN ('F', 'M', 'X')),
    CONSTRAINT ck_skater_height CHECK (height_cm IS NULL OR height_cm BETWEEN 80 AND 230),
    CONSTRAINT ck_skater_weight CHECK (weight_kg IS NULL OR weight_kg BETWEEN 20 AND 150),
    CONSTRAINT ck_skater_side CHECK (dominant_side IS NULL OR dominant_side IN ('left', 'right')),
    CONSTRAINT ck_skater_category CHECK (
        current_category IS NULL OR current_category IN ('novice', 'junior', 'senior')),
    CONSTRAINT ck_skater_discipline CHECK (
        primary_discipline IN ('mens_singles', 'ladies_singles', 'pairs', 'ice_dance'))
);
COMMENT ON TABLE  skater_profile IS '选手档案；不放头像外键以避免与 asset 形成循环引用（头像=asset.purpose=profile_photo）';

CREATE INDEX ix_skater_category ON skater_profile (current_category);

CREATE TRIGGER trg_skater_updated BEFORE UPDATE ON skater_profile
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE coach_skater (
    coach_user_id BIGINT      NOT NULL,
    skater_id     BIGINT      NOT NULL,
    started_at    DATE        NOT NULL DEFAULT CURRENT_DATE,
    ended_at      DATE,
    note          TEXT,
    PRIMARY KEY (coach_user_id, skater_id),
    CONSTRAINT fk_cs_coach FOREIGN KEY (coach_user_id) REFERENCES app_user (id),
    CONSTRAINT fk_cs_skater FOREIGN KEY (skater_id) REFERENCES skater_profile (id),
    CONSTRAINT ck_cs_period CHECK (ended_at IS NULL OR ended_at >= started_at)
);
COMMENT ON TABLE coach_skater IS '教练-选手多对多指导关系';

CREATE TABLE body_measurement (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skater_id        BIGINT       NOT NULL,
    measured_at      TIMESTAMPTZ  NOT NULL,
    height_cm        NUMERIC(5,1),
    weight_kg        NUMERIC(5,1),
    body_fat_pct     NUMERIC(4,1),
    resting_hr       SMALLINT,
    jump_reach_cm    NUMERIC(5,1),
    flexibility_score NUMERIC(5,1),
    notes            TEXT,
    CONSTRAINT uq_body_meas UNIQUE (skater_id, measured_at),
    CONSTRAINT fk_bm_skater FOREIGN KEY (skater_id) REFERENCES skater_profile (id),
    CONSTRAINT ck_bm_body_fat CHECK (body_fat_pct IS NULL OR body_fat_pct BETWEEN 0 AND 60),
    CONSTRAINT ck_bm_hr CHECK (resting_hr IS NULL OR resting_hr BETWEEN 30 AND 220),
    CONSTRAINT ck_bm_flex CHECK (flexibility_score IS NULL OR flexibility_score BETWEEN 0 AND 100)
);
COMMENT ON TABLE body_measurement IS '身体测量时序数据';

CREATE INDEX ix_body_meas_skater_time ON body_measurement (skater_id, measured_at DESC);

CREATE TABLE training_load (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skater_id      BIGINT  NOT NULL,
    train_date     DATE    NOT NULL,
    duration_min          SMALLINT,
    intensity             SMALLINT,
    fatigue_score         SMALLINT,
    soreness_score        SMALLINT,
    notes         TEXT,
    CONSTRAINT uq_training_load UNIQUE (skater_id, train_date),
    CONSTRAINT fk_tl_skater FOREIGN KEY (skater_id) REFERENCES skater_profile (id),
    CONSTRAINT ck_tl_duration CHECK (duration_min IS NULL OR duration_min > 0),
    CONSTRAINT ck_tl_intensity CHECK (intensity IS NULL OR intensity BETWEEN 1 AND 10),
    CONSTRAINT ck_tl_fatigue CHECK (fatigue_score IS NULL OR fatigue_score BETWEEN 0 AND 10),
    CONSTRAINT ck_tl_soreness CHECK (soreness_score IS NULL OR soreness_score BETWEEN 0 AND 10)
);
COMMENT ON TABLE training_load IS '训练负荷日粒度记录';

CREATE TABLE injury_record (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skater_id        BIGINT      NOT NULL,
    body_part_code   TEXT        NOT NULL,
    injury_type_code TEXT        NOT NULL,
    severity         TEXT        NOT NULL,
    occurred_on      DATE        NOT NULL,
    recovered_on     DATE,
    status           TEXT        NOT NULL DEFAULT 'active',
    risk_tags        TEXT[]      NOT NULL DEFAULT '{}',
    medical_notes    TEXT,
    recorded_by      BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_inj_skater FOREIGN KEY (skater_id) REFERENCES skater_profile (id),
    CONSTRAINT fk_inj_recorder FOREIGN KEY (recorded_by) REFERENCES app_user (id),
    CONSTRAINT ck_inj_severity CHECK (severity IN ('mild', 'moderate', 'severe')),
    CONSTRAINT ck_inj_status CHECK (status IN ('active', 'recovering', 'recovered')),
    CONSTRAINT ck_inj_status_date CHECK (
        (status = 'recovered' AND recovered_on IS NOT NULL)
        OR (status <> 'recovered' AND recovered_on IS NULL)),
    CONSTRAINT ck_inj_period CHECK (recovered_on IS NULL OR recovered_on >= occurred_on)
);
COMMENT ON TABLE  injury_record IS '伤病记录；risk_tags 经有权限人员确认后作为编排限制';
COMMENT ON COLUMN injury_record.risk_tags IS '如 {avoid_layback, limit_quads_late}';

CREATE INDEX ix_inj_skater_status ON injury_record (skater_id, status);

-- ============================================================================
-- 域 F：资产与 ML
-- ============================================================================

CREATE TABLE asset (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skater_id         BIGINT,
    asset_type        TEXT        NOT NULL,
    storage_key       TEXT        NOT NULL,
    original_filename TEXT,
    mime_type         TEXT,
    size_bytes        BIGINT,
    duration_sec      NUMERIC(8,2),
    captured_at       TIMESTAMPTZ,
    sha256            CHAR(64),
    purpose           TEXT,
    meta              JSONB       NOT NULL DEFAULT '{}'::jsonb,
    uploaded_by       BIGINT      NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    CONSTRAINT uq_asset_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_asset_skater FOREIGN KEY (skater_id) REFERENCES skater_profile (id),
    CONSTRAINT fk_asset_uploader FOREIGN KEY (uploaded_by) REFERENCES app_user (id),
    CONSTRAINT ck_asset_type CHECK (asset_type IN ('video', 'image', 'document', 'text')),
    CONSTRAINT ck_asset_size CHECK (size_bytes IS NULL OR size_bytes >= 0),
    CONSTRAINT ck_asset_duration CHECK (duration_sec IS NULL OR duration_sec > 0)
);
COMMENT ON TABLE asset IS '多模态资产元数据；文件本体存对象存储(MinIO)，软删除';

CREATE INDEX ix_asset_skater_type ON asset (skater_id, asset_type);
CREATE INDEX ix_asset_sha256 ON asset (sha256);
CREATE INDEX ix_asset_alive ON asset (id) WHERE deleted_at IS NULL;

CREATE TABLE asset_annotation (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id        BIGINT      NOT NULL,
    annotator_id    BIGINT,
    element_id      BIGINT,
    annotation_type TEXT        NOT NULL,
    t_start          NUMERIC(8,2),
    t_end            NUMERIC(8,2),
    payload         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_ann_asset FOREIGN KEY (asset_id) REFERENCES asset (id) ON DELETE CASCADE,
    CONSTRAINT fk_ann_annotator FOREIGN KEY (annotator_id) REFERENCES app_user (id),
    CONSTRAINT fk_ann_element FOREIGN KEY (element_id) REFERENCES element (id),
    CONSTRAINT ck_ann_type CHECK (annotation_type IN ('time_span', 'element_tag', 'keypoint_set', 'text_note')),
    CONSTRAINT ck_ann_time CHECK (t_end IS NULL OR t_start IS NULL OR t_end >= t_start)
);
COMMENT ON TABLE asset_annotation IS '资产标注：时间段元素标签、关键点与文本备注；支持人工复核';

CREATE INDEX ix_ann_asset ON asset_annotation (asset_id);

CREATE TABLE ml_task (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_type         TEXT        NOT NULL,
    asset_id          BIGINT,
    status            TEXT        NOT NULL DEFAULT 'pending',
    algorithm         TEXT,
    algorithm_version TEXT,
    params            JSONB       NOT NULL DEFAULT '{}'::jsonb,
    submitted_by      BIGINT      NOT NULL,
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    error             TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_task_asset FOREIGN KEY (asset_id) REFERENCES asset (id),
    CONSTRAINT fk_task_submitter FOREIGN KEY (submitted_by) REFERENCES app_user (id),
    CONSTRAINT ck_task_type CHECK (task_type IN ('pose_estimation', 'feature_extraction', 'capability_eval')),
    CONSTRAINT ck_task_status CHECK (status IN ('pending', 'running', 'succeeded', 'failed', 'cancelled')),
    CONSTRAINT ck_task_period CHECK (finished_at IS NULL OR started_at IS NULL OR finished_at >= started_at)
);
COMMENT ON TABLE ml_task IS 'ML 异步任务状态机：姿态估计/特征抽取等';

CREATE INDEX ix_task_asset ON ml_task (asset_id);
CREATE INDEX ix_task_queue ON ml_task (id) WHERE status IN ('pending', 'running');

CREATE TABLE ml_task_result (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id       BIGINT      NOT NULL,
    summary       JSONB       NOT NULL DEFAULT '{}'::jsonb,
    artifact_key  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_result_task UNIQUE (task_id),
    CONSTRAINT fk_result_task FOREIGN KEY (task_id) REFERENCES ml_task (id) ON DELETE CASCADE
);
COMMENT ON TABLE ml_task_result IS '任务结果：summary 可查询，原始大文件在对象存储(artifact_key)';

CREATE TABLE pose_frame_feature (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    task_id   BIGINT        NOT NULL,
    frame_ts  NUMERIC(10,3) NOT NULL,
    keypoints JSONB         NOT NULL,
    quality   NUMERIC(4,3),
    CONSTRAINT uq_pff UNIQUE (task_id, frame_ts),
    CONSTRAINT fk_pff_task FOREIGN KEY (task_id) REFERENCES ml_task (id) ON DELETE CASCADE,
    CONSTRAINT ck_pff_quality CHECK (quality IS NULL OR quality BETWEEN 0 AND 1)
);
COMMENT ON TABLE pose_frame_feature IS '逐帧姿态关键点；预期大表，生产建议按 task_id 分区或下沉对象存储';

-- ============================================================================
-- 域 E：能力域（时间版本化 + 证据链）
-- ============================================================================

CREATE TABLE element_attempt_log (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skater_id           BIGINT       NOT NULL,
    element_id          BIGINT       NOT NULL,
    level_code          TEXT         NOT NULL,
    attempted_at        TIMESTAMPTZ  NOT NULL,
    context             TEXT         NOT NULL DEFAULT 'training',
    is_success          BOOLEAN      NOT NULL,
    goe                 NUMERIC(4,2),
    quality_score       NUMERIC(4,2),
    position_in_program SMALLINT,
    fatigue_before      SMALLINT,
    video_asset_id      BIGINT,
    notes               TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_eal_skater FOREIGN KEY (skater_id) REFERENCES skater_profile (id),
    CONSTRAINT fk_eal_element FOREIGN KEY (element_id) REFERENCES element (id),
    CONSTRAINT fk_eal_video FOREIGN KEY (video_asset_id) REFERENCES asset (id),
    CONSTRAINT ck_eal_level CHECK (level_code IN ('N', 'B', '1', '2', '3', '4')),
    CONSTRAINT ck_eal_context CHECK (context IN ('training', 'test', 'competition')),
    CONSTRAINT ck_eal_goe CHECK (goe IS NULL OR goe BETWEEN -5 AND 5),
    CONSTRAINT ck_eal_quality CHECK (quality_score IS NULL OR quality_score BETWEEN 0 AND 10),
    CONSTRAINT ck_eal_position CHECK (position_in_program IS NULL OR position_in_program BETWEEN 1 AND 20),
    CONSTRAINT ck_eal_fatigue CHECK (fatigue_before IS NULL OR fatigue_before BETWEEN 0 AND 10)
);
COMMENT ON TABLE element_attempt_log IS '元素试做日志（原始事实）：统计的输入；position_in_program 支撑位置衰减建模';

CREATE INDEX ix_eal_skater_element_time ON element_attempt_log (skater_id, element_id, attempted_at DESC);
CREATE INDEX ix_eal_video ON element_attempt_log (video_asset_id);

CREATE TABLE element_skill_stat (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skater_id      BIGINT       NOT NULL,
    element_id     BIGINT       NOT NULL,
    level_code     TEXT         NOT NULL,
    stat_window    TEXT         NOT NULL,
    attempt_count  INTEGER      NOT NULL,
    success_rate   NUMERIC(5,4) NOT NULL,
    avg_goe        NUMERIC(4,2),
    std_goe        NUMERIC(4,2),
    avg_quality    NUMERIC(4,2),
    computed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_ess UNIQUE (skater_id, element_id, level_code, stat_window, computed_at),
    CONSTRAINT fk_ess_skater FOREIGN KEY (skater_id) REFERENCES skater_profile (id),
    CONSTRAINT fk_ess_element FOREIGN KEY (element_id) REFERENCES element (id),
    CONSTRAINT ck_ess_window CHECK (stat_window IN ('recent_30d', 'recent_90d', 'all_time')),
    CONSTRAINT ck_ess_level CHECK (level_code IN ('N', 'B', '1', '2', '3', '4')),
    CONSTRAINT ck_ess_rate CHECK (success_rate BETWEEN 0 AND 1),
    CONSTRAINT ck_ess_count CHECK (attempt_count >= 0)
);
COMMENT ON TABLE element_skill_stat IS '元素统计快照：画像与工具输入；追加策略由应用权限执行';

CREATE INDEX ix_ess_lookup ON element_skill_stat (skater_id, element_id, stat_window, computed_at DESC);

CREATE TABLE capability_snapshot (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skater_id          BIGINT        NOT NULL,
    snapshot_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    jump_consistency   NUMERIC(5,4),
    max_jump_rotations SMALLINT,
    spin_quality       NUMERIC(4,2),
    step_quality       NUMERIC(4,2),
    stamina            NUMERIC(4,2),
    flexibility        NUMERIC(4,2),
    artistry_baseline  NUMERIC(4,2),
    injury_risk_level  TEXT          NOT NULL DEFAULT 'low',
    risk_tags          TEXT[]        NOT NULL DEFAULT '{}',
    model_version      TEXT,
    source_summary     JSONB         NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT uq_cap_snap UNIQUE (skater_id, snapshot_at),
    CONSTRAINT fk_cs_snap_skater FOREIGN KEY (skater_id) REFERENCES skater_profile (id),
    CONSTRAINT ck_cap_risk CHECK (injury_risk_level IN ('low', 'medium', 'high')),
    CONSTRAINT ck_cap_scores CHECK (
        (jump_consistency IS NULL OR jump_consistency BETWEEN 0 AND 1)
        AND (spin_quality IS NULL OR spin_quality BETWEEN 0 AND 10)
        AND (step_quality IS NULL OR step_quality BETWEEN 0 AND 10)
        AND (stamina IS NULL OR stamina BETWEEN 0 AND 10)
        AND (flexibility IS NULL OR flexibility BETWEEN 0 AND 10)
        AND (artistry_baseline IS NULL OR artistry_baseline BETWEEN 0 AND 10))
);
COMMENT ON TABLE capability_snapshot IS '选手级能力画像快照（时间版本化）：决策摘要';

CREATE TABLE capability_evidence (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skater_id    BIGINT        NOT NULL,
    source_type  TEXT          NOT NULL,
    source_id    BIGINT        NOT NULL,
    element_id   BIGINT,
    metric_key   TEXT          NOT NULL,
    metric_value NUMERIC(10,3),
    unit         TEXT,
    confidence   NUMERIC(4,3),
    captured_at  TIMESTAMPTZ   NOT NULL,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fk_cev_skater FOREIGN KEY (skater_id) REFERENCES skater_profile (id),
    CONSTRAINT fk_cev_element FOREIGN KEY (element_id) REFERENCES element (id),
    CONSTRAINT ck_cev_source CHECK (source_type IN ('ml_task', 'asset', 'manual', 'form', 'competition_result')),
    CONSTRAINT ck_cev_confidence CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1)
);
COMMENT ON TABLE  capability_evidence IS '能力证据（多态来源）：source_id 不设外键，由应用层组合校验+定期孤儿检测兜底';
COMMENT ON COLUMN capability_evidence.metric_key IS '如 air_time / spin_speed / self_report_success';

CREATE INDEX ix_cev_skater_metric ON capability_evidence (skater_id, metric_key, captured_at DESC);
CREATE INDEX ix_cev_source ON capability_evidence (source_type, source_id);

-- ============================================================================
-- 域 D：赛事
-- ============================================================================

CREATE TABLE competition (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name               TEXT        NOT NULL,
    level              TEXT        NOT NULL,
    start_date         DATE        NOT NULL,
    end_date           DATE,
    location           TEXT,
    organizer          TEXT,
    ruleset_version_id BIGINT      NOT NULL,
    status             TEXT        NOT NULL DEFAULT 'planned',
    notes              TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_comp_ruleset FOREIGN KEY (ruleset_version_id) REFERENCES ruleset_version (id),
    CONSTRAINT ck_comp_level CHECK (level IN ('school', 'city', 'provincial', 'national', 'international')),
    CONSTRAINT ck_comp_status CHECK (status IN ('planned', 'ongoing', 'finished')),
    CONSTRAINT ck_comp_period CHECK (end_date IS NULL OR end_date >= start_date)
);
COMMENT ON TABLE competition IS '赛事：绑定所用规则集版本';

CREATE TRIGGER trg_comp_updated BEFORE UPDATE ON competition
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE competition_category (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    competition_id  BIGINT      NOT NULL,
    program_type_id BIGINT      NOT NULL,
    name            TEXT        NOT NULL,
    notes           TEXT,
    CONSTRAINT uq_cc UNIQUE (competition_id, name),
    CONSTRAINT fk_cc_competition FOREIGN KEY (competition_id) REFERENCES competition (id),
    CONSTRAINT fk_cc_program_type FOREIGN KEY (program_type_id) REFERENCES program_type (id)
);
COMMENT ON TABLE competition_category IS '赛事组别（竞赛单元）：编排任务的瞄准目标，如"成年组女子单人自由滑"';

CREATE TABLE skater_competition_result (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    competition_category_id BIGINT       NOT NULL,
    skater_id              BIGINT       NOT NULL,
    tes                    NUMERIC(6,2) NOT NULL,
    pcs                    NUMERIC(6,2) NOT NULL,
    deduction              NUMERIC(6,2) NOT NULL DEFAULT 0,
    total_score            NUMERIC(7,2) GENERATED ALWAYS AS (tes + pcs - deduction) STORED,
    rank                   SMALLINT,
    detail                 JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_scr UNIQUE (competition_category_id, skater_id),
    CONSTRAINT fk_scr_category FOREIGN KEY (competition_category_id) REFERENCES competition_category (id),
    CONSTRAINT fk_scr_skater FOREIGN KEY (skater_id) REFERENCES skater_profile (id),
    CONSTRAINT ck_scr_rank CHECK (rank IS NULL OR rank >= 1),
    CONSTRAINT ck_scr_scores CHECK (tes >= 0 AND pcs >= 0 AND deduction >= 0)
);
COMMENT ON TABLE skater_competition_result IS '比赛成绩；total_score 为生成列，数据库保证一致性';

CREATE INDEX ix_scr_skater ON skater_competition_result (skater_id);

-- ============================================================================
-- 域 G：编排域
-- ============================================================================

CREATE TABLE program_plan (
    id                     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skater_id              BIGINT      NOT NULL,
    competition_category_id BIGINT,
    program_type_id        BIGINT      NOT NULL,
    ruleset_version_id     BIGINT      NOT NULL,
    parent_plan_id         BIGINT,
    capability_snapshot_id BIGINT REFERENCES capability_snapshot (id),
    version_no             INTEGER     NOT NULL DEFAULT 1,
    origin                 TEXT        NOT NULL DEFAULT 'agent',
    status                 TEXT        NOT NULL DEFAULT 'generated',
    solver_config          JSONB       NOT NULL DEFAULT '{}'::jsonb,
    objective_value        NUMERIC(8,2),
    est_total_score        NUMERIC(8,2),
    risk_score             NUMERIC(6,3),
    notes                  TEXT,
    created_by             BIGINT      NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_plan_version UNIQUE NULLS NOT DISTINCT
        (skater_id, competition_category_id, program_type_id, version_no),
    CONSTRAINT fk_plan_skater FOREIGN KEY (skater_id) REFERENCES skater_profile (id),
    CONSTRAINT fk_plan_category FOREIGN KEY (competition_category_id) REFERENCES competition_category (id),
    CONSTRAINT fk_plan_program_type FOREIGN KEY (program_type_id) REFERENCES program_type (id),
    CONSTRAINT fk_plan_ruleset FOREIGN KEY (ruleset_version_id) REFERENCES ruleset_version (id),
    CONSTRAINT fk_plan_parent FOREIGN KEY (parent_plan_id) REFERENCES program_plan (id),
    CONSTRAINT fk_plan_creator FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT ck_plan_origin CHECK (origin IN ('agent', 'solver', 'manual', 'hybrid')),
    CONSTRAINT ck_plan_status CHECK (status IN ('draft', 'generated', 'coach_review', 'finalized', 'archived')),
    CONSTRAINT ck_plan_version_no CHECK (version_no >= 1)
);
COMMENT ON TABLE  program_plan IS '编排方案（版本链根）：ruleset_version_id 冻结生成时点规则，保证可复现';
COMMENT ON COLUMN program_plan.solver_config IS '求解配置快照：风险系数/统计窗口/时间限制等';

CREATE INDEX ix_plan_skater ON program_plan (skater_id, program_type_id);
CREATE INDEX ix_plan_category ON program_plan (competition_category_id);
CREATE INDEX ix_plan_parent ON program_plan (parent_plan_id);

CREATE TRIGGER trg_plan_updated BEFORE UPDATE ON program_plan
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TABLE program_element_item (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id             BIGINT       NOT NULL,
    seq_no              SMALLINT     NOT NULL,
    combo_index         SMALLINT     NOT NULL DEFAULT 1,
    element_id          BIGINT       NOT NULL,
    level_code          TEXT         NOT NULL DEFAULT 'N',
    planned_base_value  NUMERIC(5,2) NOT NULL,
    est_success_prob    NUMERIC(5,4),
    est_goe             NUMERIC(4,2),
    est_score           NUMERIC(6,2),
    risk_penalty        NUMERIC(5,2),
    is_alternate        BOOLEAN      NOT NULL DEFAULT FALSE,
    note                TEXT,
    CONSTRAINT uq_pei UNIQUE (plan_id, seq_no, combo_index),
    CONSTRAINT fk_pei_plan FOREIGN KEY (plan_id) REFERENCES program_plan (id) ON DELETE CASCADE,
    CONSTRAINT fk_pei_element FOREIGN KEY (element_id) REFERENCES element (id),
    CONSTRAINT ck_pei_seq CHECK (seq_no BETWEEN 1 AND 20),
    CONSTRAINT ck_pei_combo CHECK (combo_index BETWEEN 1 AND 3),
    CONSTRAINT ck_pei_level CHECK (level_code IN ('N', 'B', '1', '2', '3', '4')),
    CONSTRAINT ck_pei_prob CHECK (est_success_prob IS NULL OR est_success_prob BETWEEN 0 AND 1),
    CONSTRAINT ck_pei_bv CHECK (planned_base_value >= 0)
);
COMMENT ON TABLE  program_element_item IS '方案元素序列：seq_no=槽位，联合跳占一个槽位并以 combo_index 区分；分值字段为生成时点冻结值';
COMMENT ON COLUMN program_element_item.is_alternate IS '是否替补建议元素';

CREATE INDEX ix_pei_element ON program_element_item (element_id);

CREATE TABLE program_score_breakdown (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id             BIGINT       NOT NULL,
    tes_base_total      NUMERIC(7,2) NOT NULL,
    tes_goe_expected    NUMERIC(7,2) NOT NULL,
    tes_expected        NUMERIC(7,2) NOT NULL,
    pcs_expected        NUMERIC(7,2) NOT NULL,
    pcs_components      JSONB        NOT NULL,
    deduction_expected  NUMERIC(7,2) NOT NULL DEFAULT 0,
    total_expected      NUMERIC(7,2) NOT NULL,
    engine_version      TEXT,
    computed_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_psb_pcs CHECK (
        jsonb_typeof(pcs_components) = 'object'
        AND pcs_components ?& ARRAY['composition', 'presentation', 'skating_skills']
        AND (pcs_components - ARRAY['composition', 'presentation', 'skating_skills']) = '{}'::jsonb
        AND jsonb_typeof(pcs_components->'composition') = 'number'
        AND jsonb_typeof(pcs_components->'presentation') = 'number'
        AND jsonb_typeof(pcs_components->'skating_skills') = 'number'
    ),
    CONSTRAINT ck_psb_total CHECK (total_expected = tes_expected + pcs_expected - deduction_expected),
    CONSTRAINT uq_psb_plan UNIQUE (plan_id),
    CONSTRAINT fk_psb_plan FOREIGN KEY (plan_id) REFERENCES program_plan (id) ON DELETE CASCADE
);
COMMENT ON TABLE program_score_breakdown IS '方案得分拆解：PCS 必须为 composition/presentation/skating_skills 三项，2022/23 起由五项改为三项';

CREATE TABLE program_constraint_check (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    plan_id              BIGINT      NOT NULL,
    well_balanced_rule_id BIGINT,
    check_key            TEXT        NOT NULL,
    status               TEXT        NOT NULL,
    detail               JSONB       NOT NULL DEFAULT '{}'::jsonb,
    checked_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_pcc_plan FOREIGN KEY (plan_id) REFERENCES program_plan (id) ON DELETE CASCADE,
    CONSTRAINT fk_pcc_rule FOREIGN KEY (well_balanced_rule_id) REFERENCES well_balanced_rule (id),
    CONSTRAINT ck_pcc_status CHECK (status IN ('pass', 'warn', 'violation'))
);
COMMENT ON TABLE program_constraint_check IS '方案约束检查报告：逐条规则校验结果（可解释性核心证据）';

CREATE INDEX ix_pcc_plan ON program_constraint_check (plan_id, checked_at DESC);

-- ============================================================================
-- 域 H：支撑域
-- ============================================================================

CREATE TABLE dict_entry (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dict_code TEXT   NOT NULL,
    item_code TEXT   NOT NULL,
    label_zh  TEXT   NOT NULL,
    label_en  TEXT,
    sort_order       SMALLINT NOT NULL DEFAULT 0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_dict_entry UNIQUE (dict_code, item_code)
);
COMMENT ON TABLE dict_entry IS '通用字典：body_part/injury_type/age_category/unit 等';

CREATE TABLE audit_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id BIGINT,
    entity_type   TEXT        NOT NULL,
    entity_id     BIGINT      NOT NULL,
    action        TEXT        NOT NULL,
    old_value     JSONB,
    new_value     JSONB,
    ip            INET,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_audit_actor FOREIGN KEY (actor_user_id) REFERENCES app_user (id),
    CONSTRAINT ck_audit_action CHECK (
        action IN ('create', 'update', 'delete', 'login', 'generate', 'export'))
);
COMMENT ON TABLE audit_log IS '审计日志（追加式，权限层禁止 UPDATE/DELETE）；entity_type+entity_id 为多态引用';

CREATE INDEX ix_audit_entity ON audit_log (entity_type, entity_id, created_at DESC);
CREATE INDEX ix_audit_actor ON audit_log (actor_user_id, created_at DESC);

COMMIT;
