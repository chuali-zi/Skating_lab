# Java 服务端

Java 17、Spring Boot 3.5.16 和 Maven 3.9.16 构成当前唯一服务端。Maven Wrapper 固定发行版及 SHA-256。应用数据表使用本工程的 Flyway 迁移，旧 FastAPI 数据库结构保存在归档中。

## 构建与启动

在 JDK 17 或兼容版本下执行：

```powershell
cd server
.\mvnw.cmd -B --no-transfer-progress verify
```

仓库根目录的 `compose.yaml` 提供 PostgreSQL/pgvector。复制 `.env.example` 为 `.env`，设置本机数据库密码，再按根目录运行文档启动数据库。数据库应用角色和迁移角色分开，服务不能用超级用户连接。

Spring Boot 从进程环境读取以下变量，**不会自动读取仓库根目录的 `.env`**；根目录的启动脚本负责加载它，手动运行时请先将变量放入当前进程环境。

| 变量 | 作用 |
| --- | --- |
| `DB_URL` | JDBC 地址，默认 `jdbc:postgresql://127.0.0.1:15432/skating_coach` |
| `DB_USERNAME` / `DB_PASSWORD` | 受 RLS 约束的应用连接 |
| `DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD` | Flyway 独立迁移连接 |
| `SPRING_PROFILES_ACTIVE=dev` | 显式启用仅本机可调用的开发会话入口 |
| `SERVER_ADDRESS` / `SERVER_PORT` | 默认只监听 `127.0.0.1:8080` |
| `LLM_JSON_MODE` | 默认 false；支持时发送 `response_format: json_object`，GLM 开发开关启用 |
| `LLM_MAX_OUTPUT_TOKENS` | 默认 0 表示不发送 `max_tokens`；GLM 开发开关设为 32768，输出截断明确报错 |
| `LLM_ENDPOINT` | 完整 chat-completions 请求 URL，远端必须 HTTPS |
| `LLM_API_KEY` / `LLM_MODEL` | 仅服务端保存的提供商配置 |
| `LLM_TIMEOUT_SECONDS` | 单次模型调用超时，默认 90 秒 |

Windows 本机使用已存在的 `GLM_API_KEY` 做 GLM Coding Plan 开发联调时，在仓库根目录运行：

```powershell
.\scripts\start-server.ps1 -GlmCodingPlan
# 已完成构建时可以加 -SkipBuild；模型默认 glm-5.3，可用 -GlmModel 显式选择。
# 已有服务占用 8080 时，可加 -Port 8081 启动独立验证实例。
```

脚本先完成构建，再按 Process → User → Machine 顺序读取密钥，仅注入当前服务进程；不会回显、写入 `.env` 或修改 Windows 持久环境。端点为 `https://open.bigmodel.cn/api/coding/paas/v4/chat/completions`，单次请求超时 180 秒。服务退出后恢复原进程的 LLM 环境。没有该开关时，仍遵循原有 `LLM_*` 配置。

官方说明 Coding Plan 限定支持的工具和产品环境，自建产品应使用标准 API 服务；这里的显式开关用于本机开发联调，不作为生产服务默认配置。[套餐适用说明](https://docs.bigmodel.cn/cn/coding-plan/faq)、[端点说明](https://docs.bigmodel.cn/cn/coding-plan/tool/others)

真实模型联调在服务启动后执行 `node tools/live-model-smoke.mjs`，会产生实际云端调用和合成开发数据；报告在 Git 忽略的 `tmp/glm-live/`。测试通过本机服务访问模型，不读取密钥。不要与其他修改同一开发选手资料的测试并行执行，以免正确触发上下文过期。

测试另一个本机端口时，先设置 `$env:LIVE_API_BASE='http://127.0.0.1:8081/api/v1'`。Android 实测脚本为 `node tools/mobile-testing/live-coach.mjs`，使用相同变量，并需先建立对应端口的 `adb reverse tcp:8081 tcp:8081`。

环境准备好后运行：

```powershell
.\mvnw.cmd spring-boot:run
```

开发会话需要 `dev` profile 和实际 TCP loopback 地址同时成立；不会信任 `X-Forwarded-For` 来打开入口。Android 真机通过 `adb reverse tcp:8080 tcp:8080` 连接本机服务。当前尚未实现正式账号登录，非开发配置没有默认账号或固定万能 token，受保护接口默认拒绝未认证请求。

## 已实现接口

所有业务接口前缀为 `/api/v1`，使用 `Authorization: Bearer <session token>`；健康检查和受限的开发登录例外。错误响应为 `{"code":"...","message":"..."}`。

| 方法与路径 | 作用 |
| --- | --- |
| `GET /health` | 进程状态和模型配置状态，不回显配置或密钥 |
| `POST /dev/session` | 生成随机、有期限的本机开发 token 与绑定身份 |
| `GET /me` | 当前服务端确认的 `actorId/athleteId/displayName` |
| `GET /catalog/jumps` | 动作代码、名称、周数；不附未经选定规则的通用分值 |
| `POST /sessions` | 创建当前选手的会话 |
| `POST /sessions/{id}/messages` | `requestId + content` 幂等保存自由输入并进入个人记忆来源 |
| `GET /plans`、`GET /plans/{id}` | 当前选手的保存方案 |
| `POST /plans` | 保存 `title/kind/body`，`kind` 为 `MAIN` 或 `RECOVERY` |
| `PATCH /plans/{id}` | 同上并提供 `expectedRevision`，版本冲突返回 409 |
| `POST /plans/{id}/recovery` | 保存实际完成前缀、完整后续安排、主方案引用，并返回计算结果 |
| `POST /scoring/evaluate` | 对显式规则快照下的动作进行计算 |
| `POST /scoring/recovery`、`POST /scoring/compare` | 冻结前缀检查、方案差异和 BV 对比 |
| `POST /scoring/default-failure` | 可确定时选择最高 BV 连跳的默认失误情景；不存在时 404 |
| `POST /recommendations` | 保存推荐任务；包含 `requestId/sessionId/prompt`，可带 `planId/competitionKey/completedPrefix/selection`（兼容旧 `ruleBundleId` 字段） |
| `GET /recommendations/{id}` | 查询结果，按需续跑排队或租约已过期的任务 |
| GET /knowledge/expert/catalog | 当前官方比赛范围、版本、有效期、指纹、来源和数量 |
| GET /knowledge/expert?q=…&competitionKey=… | 查已核规则或训练研究，可附 revision 及重复的 sources 参数。返回回答、条件、来源、页码和匹配／冲突／过期／资料不足状态 |
| GET /assets/video-jobs | 当前运动员的最近 50 个任务、启用状态和视频规格 |
| POST /assets/video-jobs | 原始 video/mp4 请求体，单次确认头 X-Skating-Frame-Consent 取值 1，异步返回任务 |
| POST /assets/video-jobs/from-source/{id} | 从该运动员已上传的 MP4 建立观察任务，要求同样的单次授权 |
| GET /assets/video-jobs/{id}、GET /assets/video-jobs/{id}/frames/{index} | 观察、实际时间戳、待核项、已保存复核及私有 JPEG。帧索引从 0 起算 |
| PUT /assets/video-jobs/{id}/observations/{index}/review | 明确 confirmed 为 true，accept 或 correct 填写片段备注，ignore 时备注留空 |
| DELETE /assets/video-jobs/{id} | 删除片段及其帧和复核。关联建议和预案失效 |

运行根目录 scripts/install-expert-embedding.ps1 后，启动脚本会发现已验证的 BGE 文件；也可以用 SKATING_BGE_DIR 传入模型目录。公开规则精确问答可以离线使用。运行 scripts/start-server.ps1 -VideoObservations 可从本机环境读取 OPENAI_API_KEY 并启用 gpt-6-luna；手动运行 JAR 时，另设 SKATING_RUN_LIVE_LUNA=true。服务依赖 PATH 上的 ffprobe 和 ffmpeg。开启供应商后，运动员每次提交仍须在 Android 明确同意发送采样帧。应用可同时使用 -GlmCodingPlan 做编排开发联调。

记忆和知识接口由对应模块提供，见根目录文档。API 的 JSON 内部结构不是对用户输入表达的约束；用户的原话、资料和自由规则由输入/知识模块接收。

## 推荐的实际行为

推荐任务持久化到 PostgreSQL，状态为 `QUEUED → RUNNING → SUCCEEDED`，或 `PROVIDER_UNAVAILABLE/FAILED/STALE/REDACTED`。后台执行池使用数据库租约和结束时的租约检查；进程重启后，客户端继续查询即可恢复排队/过期任务。当前尚未实现无人查询时自动扫描所有选手队列的独立调度器。

执行流程：保存输入 → 提取个人记录 → 构建知识与个人上下文 → 调用云端模型 → 验证输出结构、证据引用和动作 → 使用服务端规则快照计算 → 检查上下文及方案版本 → 发布结果。网络调用发生在数据库事务之外。

没有模型配置时，输入仍保存，任务明确返回 `PROVIDER_UNAVAILABLE`。没有可执行比赛规则快照时，模型可依据已读原文提出带未知项的候选，但精确评分保持未知。模型自行输出的规则、评分和未经授权的引用不会作为权威结果。

LLM 的候选可按结构、证据引用、选择范围或冻结前缀错误修复一次，全程通过同一套服务端检查。成功结果记录 validationAttempts 和 repairReasons。文字编排通过 LanguageModelGateway 使用 chat-completions 接口，采样图像通过独立的 Responses 接口处理。推荐和失误预案可携带 videoReview，其中 id、revision 必须对应当前选手主动选择的有效复核记录。模型自行给出的分值不进入计分器。[专家知识、研究与验收](../docs/expert-knowledge-research.md)

用户更正、撤回或遗忘导致版本失效时，旧任务不能重新发布；已生成结果再次读取也检查上下文版本。保存的历史方案通过 `contextInvalidated` 提醒来源发生变化，并阻止直接作为推荐或预案基线；读取清理后的新版本、明确重新保存当前编排后才能继续。方案 JSON 通过类型投影去除未知嵌套字段，避免产生不受来源管理的私人证据副本。恢复方案的已发生前缀在保存后冻结，纠正实际情景应创建新预案。

## 验证范围

单元测试覆盖身份隔离、开发入口、token 到期、真实 HTTP 协议适配、无模型配置、模型引用与实际前缀校验，以及独立计分模块。HTTP 模型测试使用本机 fixture，不需要云端密钥，也不表示真实模型教练效果已经验证。

云端开发联调独立于上述 fixture 测试：2026-11-15 使用真实 GLM 接口检查个人资料更正、知识范围、冻结前缀、自由输入提取、来源撤回及 Android 操作。实际结果和边界见 [GLM 联调记录](../docs/glm-live-validation-2026-11-15.md)。新增数据库集成测试还验证具体错误反馈能修复虚构引用、被拒绝的原文不进入最终结果、连续失败在一次修复后终止。

数据库集成测试验证 PostgreSQL、Flyway、行级隔离及资料生命周期。本轮已使用真实 OpenAI 接口跑完三段比赛录像，并在 Android API 36 模拟器查看证据帧、人工纠正、保存关联预案。正式账号接入、长视频和专业裁判盲评仍在现有验收范围以外。[实拍录像逐段记录](../docs/public-skating-validation.md) · [规则检索与编排实测](../docs/expert-knowledge-research.md)
