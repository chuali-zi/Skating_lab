-- ============================================================================
-- Skating Coach · 最小种子数据（v0.2）
-- ⚠ 免责声明：本文中的 Base Value / GOE 参数 / 数量限制均为【演示示意值】，
--    正式数据必须以当季 ISU 官方通讯及协会规程为准，双人核对后录入。
-- 执行方式：psql -d skating_coach -f db/schema/002_seed_minimal.sql
-- ============================================================================

BEGIN;

-- ----------------------------------------------------------------------------
-- 角色
-- ----------------------------------------------------------------------------
INSERT INTO role (id, code, name_zh, description, is_system) OVERRIDING SYSTEM VALUE VALUES
    (1, 'admin',  '管理员', '系统管理、规则库维护',        TRUE),
    (2, 'coach',  '教练',   '编排决策与微调',              TRUE),
    (3, 'skater', '选手',   '查看本人档案与方案',          TRUE),
    (4, 'medic',  '队医',   '伤病与体能数据录入',          TRUE),
    (5, 'viewer', '观察者', '只读演示账号',                TRUE);

-- ----------------------------------------------------------------------------
-- 元素类别
-- ----------------------------------------------------------------------------
INSERT INTO element_category (id, code, name_zh, sort_order) OVERRIDING SYSTEM VALUE VALUES
    (1, 'jump',    '跳跃',     1),
    (2, 'spin',    '旋转',     2),
    (3, 'step',    '接续步',   3),
    (4, 'choreo',  '编排步伐', 4);

-- ----------------------------------------------------------------------------
-- 元素字典（跳跃按 ISU 常用记号；旋转/步伐不含级别，级别由定价表展开）
-- ----------------------------------------------------------------------------
INSERT INTO element (id, code, name_en, name_zh, category_id, jump_rotations, has_flying,
                     has_change_foot, is_combinable, description) OVERRIDING SYSTEM VALUE VALUES
    -- 跳跃（周数, is_combinable=TRUE）
    ( 1, '1A',  'Single Axel',       '一周半跳(阿克塞尔)', 1, 1, FALSE, FALSE, TRUE, NULL),
    ( 2, '2A',  'Double Axel',       '两周半跳',           1, 2, FALSE, FALSE, TRUE, NULL),
    ( 3, '3T',  'Triple Toe Loop',   '三周后外点冰跳',     1, 3, FALSE, FALSE, TRUE, NULL),
    ( 4, '3S',  'Triple Salchow',    '三周后内结环跳',     1, 3, FALSE, FALSE, TRUE, NULL),
    ( 5, '3Lo', 'Triple Loop',       '三周后外结环跳',     1, 3, FALSE, FALSE, TRUE, NULL),
    ( 6, '3F',  'Triple Flip',       '三周后内点冰跳',     1, 3, FALSE, FALSE, TRUE, NULL),
    ( 7, '3Lz', 'Triple Lutz',       '三周勾手跳',         1, 3, FALSE, FALSE, TRUE, NULL),
    ( 8, '3A',  'Triple Axel',       '三周半跳',           1, 3, FALSE, FALSE, TRUE, NULL),
    ( 9, '4T',  'Quadruple Toe Loop','四周后外点冰跳',     1, 4, FALSE, FALSE, TRUE, NULL),
    (10, '4S',  'Quadruple Salchow', '四周后内结环跳',     1, 4, FALSE, FALSE, TRUE, NULL),
    (11, '4Lo', 'Quadruple Loop',    '四周后外结环跳',     1, 4, FALSE, FALSE, TRUE, NULL),
    (12, '4F',  'Quadruple Flip',    '四周后内点冰跳',     1, 4, FALSE, FALSE, TRUE, NULL),
    (13, '4Lz', 'Quadruple Lutz',    '四周勾手跳',         1, 4, FALSE, FALSE, TRUE, NULL),
    (14, '4A',  'Quadruple Axel',    '四周半跳',           1, 4, FALSE, FALSE, TRUE, NULL),
    -- 旋转（示意）
    (20, 'USp',  'Upright Spin',          '直立旋转',     2, NULL, FALSE, FALSE, FALSE, NULL),
    (21, 'CSp',  'Camel Spin',            '燕式旋转',     2, NULL, FALSE, FALSE, FALSE, NULL),
    (22, 'SSp',  'Sit Spin',              '蹲踞旋转',     2, NULL, FALSE, FALSE, FALSE, NULL),
    (23, 'LSp',  'Layback Spin',          '躬身旋转',     2, NULL, FALSE, FALSE, FALSE, NULL),
    (24, 'CoSp', 'Spin Combination',      '联合旋转',     2, NULL, FALSE, TRUE,  FALSE, NULL),
    (25, 'FSSp', 'Flying Sit Spin',       '飞身蹲转',     2, NULL, TRUE,  FALSE, FALSE, NULL),
    -- 步伐 / 编排
    (30, 'StSq', 'Step Sequence',         '接续步',       3, NULL, FALSE, FALSE, FALSE, NULL),
    (31, 'ChSq', 'Choreographic Sequence','编排步伐',     4, NULL, FALSE, FALSE, FALSE, NULL);

-- ----------------------------------------------------------------------------
-- 节目类型
-- ----------------------------------------------------------------------------
INSERT INTO program_type (id, code, name_zh, discipline, duration_min_sec, duration_max_sec, notes)
OVERRIDING SYSTEM VALUE VALUES
    (1, 'SP', '短节目', 'singles', 150, 170, '2 分 40 秒 ±10 秒（示意）'),
    (2, 'FS', '自由滑', 'singles', 230, 280, '时长范围仅作测试夹具，不代表任何正式组别');

-- ----------------------------------------------------------------------------
-- 规则集版本（示意）
-- ----------------------------------------------------------------------------
INSERT INTO ruleset_version (id, season_code, discipline, name, isu_communication_no,
                             effective_from, source_url, status) OVERRIDING SYSTEM VALUE VALUES
    (1, '2026-27', 'singles', '2026-27 赛季单人滑规则集（示意）', 'TBD',
     '2026-07-01', NULL, 'draft');

-- ----------------------------------------------------------------------------
-- 基础分测试夹具（全部为示意，未核对，不可用于正式推荐）
-- ----------------------------------------------------------------------------
INSERT INTO element_level_value (ruleset_version_id, element_id, level_code, base_value) VALUES
    -- 跳跃：级别恒为 N
    (1,  1, 'N',  1.10), (1,  2, 'N',  3.30), (1,  3, 'N',  4.20), (1,  4, 'N',  4.30),
    (1,  5, 'N',  4.90), (1,  6, 'N',  5.30), (1,  7, 'N',  5.90), (1,  8, 'N',  8.00),
    (1,  9, 'N',  9.50), (1, 10, 'N',  9.70), (1, 11, 'N', 10.50), (1, 12, 'N', 11.00),
    (1, 13, 'N', 11.50), (1, 14, 'N', 12.50),
    -- 旋转：B/1/2/3/4（示意）
    (1, 20, 'B', 1.00), (1, 20, '1', 1.40), (1, 20, '2', 1.80), (1, 20, '3', 2.20), (1, 20, '4', 2.60),
    (1, 21, 'B', 1.10), (1, 21, '1', 1.50), (1, 21, '2', 1.90), (1, 21, '3', 2.40), (1, 21, '4', 2.80),
    (1, 22, 'B', 1.10), (1, 22, '1', 1.50), (1, 22, '2', 1.90), (1, 22, '3', 2.40), (1, 22, '4', 2.80),
    (1, 23, 'B', 1.10), (1, 23, '1', 1.50), (1, 23, '2', 1.90), (1, 23, '3', 2.40), (1, 23, '4', 2.80),
    (1, 24, 'B', 1.20), (1, 24, '1', 1.60), (1, 24, '2', 2.00), (1, 24, '3', 2.50), (1, 24, '4', 2.90),
    (1, 25, 'B', 1.30), (1, 25, '1', 1.70), (1, 25, '2', 2.20), (1, 25, '3', 2.60), (1, 25, '4', 3.00),
    -- 接续步：B/1/2/3/4（示意）
    (1, 30, 'B', 1.10), (1, 30, '1', 1.60), (1, 30, '2', 2.10), (1, 30, '3', 2.60), (1, 30, '4', 3.10),
    -- 编排步伐：固定分值（示意）
    (1, 31, 'N', 1.00);

-- ----------------------------------------------------------------------------
-- 规则参数（示意值，正式值以官方通讯为准）
-- ----------------------------------------------------------------------------

INSERT INTO ruleset_parameter (ruleset_version_id, program_type_id, param_key, param_value, unit, description) VALUES
    (1, 1,    'pcs_factor',              1.00,  'ratio', '短节目 PCS 因子（示意）'),
    (1, 2,    'pcs_factor',              1.33,  'ratio', '自由滑 PCS 因子（示意）'),
    (1, NULL, 'jump_goe_step_pct',      10.00,  'pct',   '跳跃 GOE 每级对应 BV 的百分比（示意）'),
    (1, NULL, 'fall_deduction',          1.00,  'point', '每次跌倒扣分（示意）'),
    (1, NULL, 'repeat_solo_jump_bv_pct',70.00,  'pct',   '同一跳跃第二次单独执行 +REP 仅计 BV 百分比（示意）'),
    (1, 1,    'duration_target_sec',   160.00,  'sec',   '短节目目标时长（示意）'),
    (1, 2,    'duration_target_sec',   240.00,  'sec',   '成年组女子自由滑目标时长（示意）');

-- ----------------------------------------------------------------------------
-- well-balanced 条款（示意；target：category 1=jump 2=spin 3=step 4=choreo）
-- ----------------------------------------------------------------------------

INSERT INTO well_balanced_rule (ruleset_version_id, program_type_id, skater_category, rule_key,
                                constraint_kind, target_category_id, target_element_id,
                                min_count, max_count, penalty_note) VALUES
    -- 自由滑（通用）
    (1, 2, NULL, 'element_total',        'count_max',  NULL, NULL, NULL, 12,  '超出部分不计分'),
    (1, 2, NULL, 'jump_element',         'count_max',  1,    NULL, NULL, 7,   '超出跳跃元素不计分'),
    (1, 2, NULL, 'combo_jump',           'count_max',  1,    NULL, NULL, 3,   '超出联合跳按序列规则处理'),
    (1, 2, NULL, 'triple_combo',         'count_max',  1,    NULL, NULL, 1,   '三连跳至多一个'),
    (1, 2, NULL, 'spin_element',         'count_max',  2,    NULL, NULL, 4,   '超出旋转不计分'),
    (1, 2, NULL, 'step_seq',             'count_max',  3,    NULL, NULL, 1,   '超出接续步不计分'),
    (1, 2, NULL, 'choreo_seq',           'count_max',  4,    NULL, NULL, 1,   '超出编排步伐不计分'),
    (1, 2, NULL, 'same_jump_repeat',     'count_max',  NULL, NULL, NULL, 2,   '同一跳跃至多两次；第二次若均单独执行则 +REP 计 70% BV'),
    (1, 2, NULL, 'spin_combination',     'count_min',  2,    NULL, 1,    NULL, '缺少联合旋转不构成均衡节目'),
    (1, 2, NULL, 'step_seq_required',    'required',   3,    NULL, 1,    NULL, '必须包含一个接续步'),
    -- 短节目（通用）
    (1, 1, NULL, 'element_total',        'count_max',  NULL, NULL, NULL, 7,  '短节目共 7 个元素'),
    (1, 1, NULL, 'jump_element',         'count_max',  1,    NULL, NULL, 3,  '含一个联合跳'),
    (1, 1, NULL, 'spin_element',         'count_max',  2,    NULL, NULL, 3,  NULL),
    (1, 1, NULL, 'step_seq_required',    'required',   3,    NULL, 1,    NULL, '必须包含一个接续步'),
    (1, 1, 'senior', 'sp_required_axel','required',   NULL, 2,    1,    NULL, '成年组短节目须含 2A（示意）');

-- ----------------------------------------------------------------------------
-- 通用字典（节选）
-- ----------------------------------------------------------------------------
INSERT INTO dict_entry (dict_code, item_code, label_zh, label_en, sort_order) VALUES
    ('body_part',    'shoulder', '肩部', 'shoulder', 1),
    ('body_part',    'back',     '腰背', 'back',     2),
    ('body_part',    'hip',      '髋部', 'hip',      3),
    ('body_part',    'groin',    '腹股沟', 'groin',  4),
    ('body_part',    'knee',     '膝部', 'knee',     5),
    ('body_part',    'ankle',    '踝部', 'ankle',    6),
    ('injury_type',  'strain',   '拉伤', 'strain',   1),
    ('injury_type',  'sprain',   '扭伤', 'sprain',   2),
    ('injury_type',  'fracture', '骨折', 'fracture', 3),
    ('injury_type',  'tendinitis','肌腱炎','tendinitis', 4),
    ('age_category', 'novice',   '新手组', 'novice',  1),
    ('age_category', 'junior',   '青年组', 'junior',  2),
    ('age_category', 'senior',   '成年组', 'senior',  3),
    ('unit',         'sec',      '秒',   'second',   1),
    ('unit',         'rpm',      '转/分', 'rpm',     2),
    ('unit',         'cm',       '厘米', 'cm',       3);

-- 同步 IDENTITY 序列（显式 id 插入后）
SELECT setval('role_id_seq',                (SELECT MAX(id) FROM role));
SELECT setval('element_category_id_seq',    (SELECT MAX(id) FROM element_category));
SELECT setval('element_id_seq',             (SELECT MAX(id) FROM element));
SELECT setval('program_type_id_seq',        (SELECT MAX(id) FROM program_type));
SELECT setval('ruleset_version_id_seq',     (SELECT MAX(id) FROM ruleset_version));

COMMIT;
