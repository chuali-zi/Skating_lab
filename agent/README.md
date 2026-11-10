# agent：智能体总控骨架

LLM Agent 是首版核心，负责理解、构思、工具选择与解释。这里仅定义接口，不提供可运行的智能体、模型 SDK 或权重。完整设计见 [技术设计](../docs/technical-design.md)。

| 文件 | 职责 |
| --- | --- |
| `skating_agent/contracts.py` | 会话上下文、角色、工具请求结果、人工复核命令 |
| `skating_agent/orchestrator.py` | 总控推进、人工确认后恢复与取消入口 |
| `skating_agent/subagents.py` | 多模态、能力画像、规则检索、规划与解释角色 |
| `skating_agent/tools.py` | 视觉观察、可选姿态、画像、规则、求解、复检和评分端口 |
| `skating_agent/model_gateway.py` | 厂商中立的文本与视觉部署接口 |
| `skating_agent/memory.py` | PostgreSQL 会话、输出、轨迹、证据与确认事务入口 |

后续由 `backend` 注入仓储、评分、校验和求解工具，`ml` 注入感知工具。总控不能直接执行模型生成的 SQL 或代码，不能自行改写规则与分数，不能绕过人工确认。子角色可在同一进程运行，独立调用可并行调度；当前没有实现调度器。

开发时可在仓库虚拟环境安装：`python3 -m pip install -e ./agent`。该命令只安装接口包；模型部署别名和调用配置后续由环境注入。首先实现结构验证、工具白名单、记忆落库与确认门禁，再接入有限步数的总控循环。
