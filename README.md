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

**当前产品以 2026-11-14 的手绘需求、后续用户修正和 PRD v0.8 为准。首版用户端是原生 Android。** 手机负责交互，Java 服务负责计算、检索、外部模型调用和结果核验。服务端结合已有模型、花滑知识库及每位选手的档案形成建议。本机已联调 GLM-5.3 编排和 OpenAI gpt-6-luna 视频帧观察。中文 BGE 检索模型在服务端 CPU 运行。

三份架构文档记录 v0.1 目标设计。Android、Java 服务、计算、数据库和知识处理的早期实现见[11 月 14 日记录](docs/implementation-status-2026-11-14.md)。当前规则知识、真实比赛裁判表核对、手机复核和云端编排验证见[专家知识与验收](docs/expert-knowledge-research.md)。产品以主编排编辑和失误预案为核心。

日常开发统一在本仓的 Kotlin Android 应用和 Java 17 Spring Boot 服务上。手机端使用 Jetpack Compose，服务端管理账户、知识、推荐、确定性计算、视频抽帧和人工复核。完整 FastAPI 源线保存在 Gitee 固定提交，早期骨架集中在归档目录。[专家知识与验收](docs/expert-knowledge-research.md) · [FastAPI 迁移与归档](archive/fastapi/README.md)

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

在本机配置 LLM_ENDPOINT、LLM_API_KEY 和 LLM_MODEL 可以启用云端教练。专家问答直接读取已核规则、分值表和训练研究。运行下面的脚本可安装带指纹校验的中文 BGE，供规则主题排序和私有资料混合检索使用；开启视频观察需要可用的 FFmpeg、FFprobe 和服务进程可读取的 OPENAI_API_KEY。

~~~powershell
.\scripts\install-expert-embedding.ps1
.\scripts\start-server.ps1 -GlmCodingPlan -VideoObservations
~~~

Windows 已设置 `GLM_API_KEY` 时，可用 `scripts/start-server.ps1 -GlmCodingPlan` 启动本机真实模型开发联调；密钥仅从环境读取，具体行为和适用范围见[服务端说明](server/README.md)。

2026-11-15 的 GLM 实际调用、个人记忆/知识验证和 Android 端到端结果见 [真实模型联调记录](docs/glm-live-validation-2026-11-15.md)。它验证开发链路，不代替教练盲评或真实运动员效果评估。

Android「资料 → 问滑冰教练」可查看回答、适用条件、冲突及原始出处。「资料 → 视频观察与复核」支持提交 H.264 MP4、查看实际采样时间、对照图像、确认或纠正观察，并把复核备注用于当前主方案的失误预案。自定义比赛可继续上传专属规程。开发验收脚本沿用明确标注的样例规则。[服务端运行和接口](server/README.md)

## 核心流程

1. 选择单跳和连跳，支持高周数带出低周数动作及连跳搜索。
2. 描述稳定性、疲劳与偏好，选择比赛或提供任意表达方式的规则资料，由系统负责理解。
3. 生成贴合个人能力的主编排，编辑动作、拖拽排序、调整时间安排，并查看 BV 与推荐取舍。后半程时间阈值由比赛确定。
4. 默认准备最高 BV 连跳未接上的预案，也可选择其他失误情景。
5. 调整并保存主编排与预案，供训练、赛前回看和讨论使用。

## 仓库

项目关联地址：[chuali-zi/Skating_lab](https://github.com/chuali-zi/Skating_lab)。

当前产品需求统一维护在上述文档中，原始手绘图保留为需求来源。
