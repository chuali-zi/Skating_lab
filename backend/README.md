# backend：API 与确定性工具骨架

FastAPI 目前仅有健康检查，业务模块没有端点实现。LLM Agent 是编排总控；本模块负责权限、事务、数据查询及确定性工具，通过应用服务调度 [agent](../agent/README.md)。

| 路径 | 职责 |
| --- | --- |
| `app/main.py` | `/api/health`；业务路由统一挂载 `/api/v1` |
| `app/api/v1/agent.py` | 会话、消息、复核、取消入口占位 |
| `app/api/v1/skaters.py`、`assets.py`、`rules.py` | 选手、授权资料、版本规则 |
| `app/api/v1/programs.py` | 方案、解释、证据、微调版本与定稿 |
| `app/api/v1/optimizer.py` | 旧入口兼容占位，未来转交会话服务 |
| `app/services/agent_service.py` | 创建会话、授权与人工复核事务边界 |
| `app/services/optimizer_service.py` | Agent 调用的约束求解工具 |
| `app/services/validation_service.py` | 独立复检、规则覆盖与硬限制检查 |
| `app/services/scoring_engine.py` | 版本规则评分，输出 TES、三项 PCS、扣分和来源 |
| `app/models/` | 对照 001 和 003 建立 ORM 与迁移的 TODO |

根目录执行启动命令见 [仓库说明](../README.md)。后端目录下读取 `.env`，调整连接信息；健康检查不需要数据库或模型，但不能证明完整产品可用。

后续 P0 必须实现权限与授权、会话持久化、事务复核、预算预留、模型与工具调用记录以及定稿门禁。模型调用放在事务外；资料和检索内容视为不可信输入。评分、规则和解释接口约定见 [技术设计](../docs/technical-design.md)。
