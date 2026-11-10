# ml —— 多模态 / 姿态估计 / 能力特征抽取（占位包）

本包是视频与照片分析流水线的家。当前**只有接口占位**，不安装重依赖、不下载模型权重。

## 模块划分

| 模块 | 职责 | 阶段 |
| --- | --- | --- |
| `skating_ml/pose/` | 姿态估计：视频 → 逐帧关键点（MVP 用 MediaPipe Pose，CPU 可跑） | P1 |
| `skating_ml/features/` | 特征抽取：关键点序列 → 滞空时间/转速/跳跃高度等 → `capability_evidence` | P1 |
| `skating_ml/llm/` | LLM 辅助：方案解释、自述文本抽取（**不参与求解**，人在环确认） | P1 |

## 边界声明（与 PRD §9.3 一致）

- 核心编排由约束优化（CP-SAT）完成，本包产物只作为**带置信度的能力证据**；
- MVP 阶段能力数据以"表单录入 + 人工标注"占位，流水线接入后平滑替换；
- LLM 仅做解释与抽取辅助，任何自动结果需人工确认才入库生效。

## 实现入口（TODO）

1. `pose/estimator.py`：MediaPipe Pose 实现，输出写入 `pose_frame_feature`；
2. `features/capability_extractor.py`：从关键点序列估计 metric_key（air_time / spin_speed / jump_height）；
3. 与 backend 的 `ml_task` 状态机对接（Celery worker 调用）。
