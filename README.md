# Skating Lab

面向花样滑冰选手的移动端跳跃编排与失误预案工具。

选手选择会做的单跳与连跳，描述稳定性、疲劳特点和使用偏好，选择比赛或自由提供规则资料，得到贴合自身能力、可以自由编辑的跳跃编排与失误补救方案。

## 当前产品文档

- [产品需求文档](docs/PRD.md)：目标产品、输入、主编排编辑、后半程安排、失误预案、手机使用流程和验收场景。
- [截图需求对照](docs/requirements-map.md)：逐项对应手绘界面，并列出为完整体验补齐的行为。
- [手绘需求原图](docs/requirements-sketch-2026-11-14.jpg)：本次产品整理使用的原始截图。
- [计算与建议引擎技术调研](docs/engine-research-2026-11-14.md)：花滑模型、视频理解、个性化决策、技术候选与验证计划。
- [整体架构设计](docs/architecture.md)：Android、Java 模块、模型与计算职责、推荐链路、数据契约及实施顺序。
- [个性化记忆设计](docs/personal-memory-design.md)：每位选手的长期档案、当前情境、来源、自动更新、冲突、纠正与遗忘。
- [知识库与混合检索设计](docs/knowledge-rag-design.md)：文档入库、规则版本、结构化查询、RAG、原文展开、引用与知识管理。

**当前产品以 2026-11-14 的手绘需求、后续用户修正和 PRD v0.8 为准。首版用户端已确定为原生 Android，仅接收服务并承担交互。** 已排除 Web 前端、网页套壳、Python 后端和端侧推理。建议引擎采用云端 LLM + 花滑知识库/RAG + 个人长期档案；当前不训练、不微调模型，不采购训练 GPU。本轮架构基线采用 Kotlin + Jetpack Compose、Java + Spring Boot、PostgreSQL/pgvector 与对象存储；具体云端模型和依赖版本通过后续验证选定，视频由服务端调用云端模型分析。

三份架构文档当前为 v0.1 目标设计。Android、Java 服务、计算、数据库和知识处理已建立首个可构建的工程闭环，实际完成范围与验证见[本轮实现记录](docs/implementation-status-2026-11-14.md)。产品仍以主编排编辑和失误预案为核心，LLM 在服务端提供个性化建议。

已有 Python 后端、Agent 和 ML 文件仅为旧方案骨架，不代表当前技术路线，也未完成新版产品实现。

## 当前工程与本机启动

- [Java 服务](server/README.md)：Java 17、Spring Boot、Maven Wrapper；身份范围、方案、推荐任务、计算、记忆与知识 API。
- [Android 客户端](docs/android-development.md)：Kotlin/Compose、构建 APK、连接本机服务与设备验证说明。
- [Android 设备自测](docs/android-device-testing.md)：专用模拟器、Mobile MCP 连接、原生界面回归与缺陷修复记录。
- [计算模块](docs/scoring-implementation.md)：确定性 BV、时间条件、选择范围和冻结前缀。
- [专业知识来源](docs/knowledge-sources-2026-11-14.md)：8 个已核对来源入口、研究线索及实际资料入库验证。

Windows PowerShell 7、Docker Desktop 和完整 JDK 17 就绪后：

```powershell
.\scripts\init-local.ps1
.\scripts\start-server.ps1
```

初始化脚本创建忽略入库的 `.env` 和独立随机数据库密码，并启动绑定本机 `15432` 端口的 PostgreSQL/pgvector。服务启动在 `127.0.0.1:8080`，默认本机配置显式启用开发会话。正式配置没有开发登录；生产身份接入尚待实现。

在 `.env` 配置实际 `LLM_ENDPOINT`、`LLM_API_KEY`、`LLM_MODEL` 后，服务才能调用云端教练。Embedding 有独立配置；未配置时检索明确降级为关键词模式。当前使用实际 HTTP 模型适配器，Spring AI 运行依赖尚未接入。

Windows 已设置 `GLM_API_KEY` 时，可用 `scripts/start-server.ps1 -GlmCodingPlan` 启动本机真实模型开发联调；密钥仅从环境读取，具体行为和适用范围见[服务端说明](server/README.md)。

2026-11-15 的 GLM 实际调用、个人记忆/知识验证和 Android 端到端结果见 [真实模型联调记录](docs/glm-live-validation-2026-11-15.md)。它验证开发链路，不代替教练盲评或真实运动员效果评估。

`scripts/smoke-api.ps1` 可验证记忆操作、明确测试规则下的 BV 和方案保存；其中测试规则标明为开发样例，不是正式赛事规则。上传资料可保留原件并解析文本；自动视觉理解、完整规则包构建和真实教练效果仍在后续实施范围。

## 核心流程

1. 选择单跳和连跳，支持高周数带出低周数动作及连跳搜索。
2. 描述稳定性、疲劳与偏好，选择比赛或提供任意表达方式的规则资料，由系统负责理解。
3. 生成贴合个人能力的主编排，编辑动作、拖拽排序、调整时间安排，并查看 BV 与推荐取舍。后半程时间阈值由比赛确定。
4. 默认准备最高 BV 连跳未接上的预案，也可选择其他失误情景。
5. 调整并保存主编排与预案，供训练、赛前回看和讨论使用。

## 仓库

项目关联地址：[chuali-zi/Skating_lab](https://github.com/chuali-zi/Skating_lab)。

当前产品需求统一维护在上述文档中，原始手绘图保留为需求来源。
