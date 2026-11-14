# Java 服务端

首个可运行后端：Java 17、Spring Boot 3.5.16、Maven 3.9.16。Maven Wrapper 固定发行版及 SHA-256，无需预先安装 Maven。数据库与知识迁移属于这个工程；不要同时执行旧 `db/schema/00*.sql`。

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
| `LLM_ENDPOINT` | 完整 chat-completions 请求 URL，远端必须 HTTPS |
| `LLM_API_KEY` / `LLM_MODEL` | 仅服务端保存的提供商配置 |
| `LLM_TIMEOUT_SECONDS` | 单次模型调用超时，默认 90 秒 |

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

记忆和知识接口由对应模块提供，见根目录文档。API 的 JSON 内部结构不是对用户输入表达的约束；用户的原话、资料和自由规则由输入/知识模块接收。

## 推荐的实际行为

推荐任务持久化到 PostgreSQL，状态为 `QUEUED → RUNNING → SUCCEEDED`，或 `PROVIDER_UNAVAILABLE/FAILED/STALE/REDACTED`。后台执行池使用数据库租约和结束时的租约检查；进程重启后，客户端继续查询即可恢复排队/过期任务。当前尚未实现无人查询时自动扫描所有选手队列的独立调度器。

执行流程：保存输入 → 提取个人记录 → 构建知识与个人上下文 → 调用云端模型 → 验证输出结构、证据引用和动作 → 使用服务端规则快照计算 → 检查上下文及方案版本 → 发布结果。网络调用发生在数据库事务之外。

没有模型配置时，输入仍保存，任务明确返回 `PROVIDER_UNAVAILABLE`。没有可执行比赛规则快照时，模型可依据已读原文提出带未知项的候选，但精确评分保持未知。模型自行输出的规则、评分和未经授权的引用不会作为权威结果。

LLM 输出候选目前使用一次有界的格式修复重试；并未实现任意工具循环、多模态视频教练或自动生成可执行新规则。HTTP 适配层对标准 chat-completions 格式进行真实请求，通过 `LanguageModelGateway` 隔离业务。本轮尚未引入 Spring AI 运行依赖；原架构中采用 Spring AI 的适配方案仍待集成，当前不能宣称 Spring AI 已经接入。后续可增加 Spring AI 或其他厂商适配，而无需改动个人记忆和业务接口。

用户更正、撤回或遗忘导致版本失效时，旧任务不能重新发布；已生成结果再次读取也检查上下文版本。保存的历史方案通过 `contextInvalidated` 提醒来源发生变化，并阻止直接作为推荐或预案基线；读取清理后的新版本、明确重新保存当前编排后才能继续。方案 JSON 通过类型投影去除未知嵌套字段，避免产生不受来源管理的私人证据副本。恢复方案的已发生前缀在保存后冻结，纠正实际情景应创建新预案。

## 验证范围

单元测试覆盖身份隔离、开发入口、token 到期、真实 HTTP 协议适配、无模型配置、模型引用与实际前缀校验，以及独立计分模块。HTTP 模型测试使用本机 fixture，不需要云端密钥，也不表示真实模型教练效果已经验证。

数据库集成测试验证 PostgreSQL/Flyway/RLS 及资料生命周期；以实际测试报告判断是否执行。Android 的实际画面、正式账号、外部云端模型效果和长视频理解需要相应环境与真实样本继续验证。
