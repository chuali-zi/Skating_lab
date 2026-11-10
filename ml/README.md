# ml：多模态理解与运动工具骨架

视觉语言理解是首版必需路径，姿态估计是辅助工具。整体目标澄清、能力权衡、方案构思和解释属于 [agent](../agent/README.md)，本包通过接口提供感知能力，不实现完整流程、不下载模型权重。

| 模块 | 输入与输出 | 边界 |
| --- | --- | --- |
| `skating_ml/vision/understanding.py` | 受授权视频片段或照片 → 语义观察、时间段、不确定性 | 必需；记录实际输入范围，关键结论待人工确认 |
| `skating_ml/pose/estimator.py` | 视频 → 关键点和相对运动线索 | 可选，质量不足则拒绝测量，不是唯一感知路径 |
| `skating_ml/features/capability_extractor.py` | 运动工具结果 → 带来源的候选特征 | 不将置信度当成功率，不自动改写画像 |
| `skating_ml/llm/assistant.py` | 自述 → 事实、偏好、冲突与待确认项 | 只做输入适配，总控与个性化规划位于 agent 包 |

后续通过 `agent_tool_call`、`agent_llm_output` 和 `agent_evidence` 记录调用、输出和引用；运动测量继续使用 `ml_task`、`ml_task_result`、`pose_frame_feature`。确认后建立能力证据、画像与采用关系。接口边界见 [技术设计](../docs/technical-design.md)。

开发可执行 `python3 -m pip install -e ./ml` 安装空依赖骨架。模型适配器使用环境中的部署别名；没有模型配置时提示不可用，不能把人工标注伪装为自动理解。
