# Skating Coach：花样滑冰 AI 教练与个性化动作编排

选手可以交视频、照片、表单和自述，说明自己的能力、偏好与目标赛事；智能教练整理资料、澄清疑问，提出贴合个人情况的编排，并解释“为什么这样排”。教练能够确认关键事实、比较取舍、微调和定稿，历史方案保留当时的依据。

产品同时面向校赛“计算机数据库设计大赛”和国赛“中国大学生计算机设计大赛”：前者展示数据管理与可追溯证据链，后者展示多模态理解、智能体规划和可解释的人机协作。

**当前为文档与骨架阶段。** 可初始化数据库，后端有健康检查，前端有页面占位；尚无真实模型接入、智能体运行时、评分或求解实现。设计中的验收目标不等于已取得的效果。

## 文档入口

- [产品需求](docs/PRD.md)：用户、场景、产品能力、优先级与验收边界，保持纯产品表述。
- [技术设计](docs/technical-design.md)：必需的 LLM Agent 总控、多模态、工具、人工确认、成本及评估；总体技术内容集中于此。
- [数据库设计](docs/database-design.md)：保留既有八域，扩展为九域四十四表，含数据字典、ER、约束分工和查询。
- [数据库初始化](db/README.md)：可执行结构、示意种子及血缘查询。

Markdown 是本次评审的有效版本；旧 PDF 已移除，避免与新的产品边界和架构冲突。后续如需 PDF，应从当前 Markdown 统一导出。

## 目录说明

```text
skating-coach/
├── docs/                 # 产品需求、技术设计、数据库设计
├── db/
│   ├── schema/           # 001 基础结构、002 基础种子、003 Agent 扩展、004 合成血缘种子
│   └── examples/         # 可执行追溯查询
├── agent/                # 总控、子角色、工具、模型与记忆接口
├── backend/              # API 与事务、规则校验、评分、约束工具骨架
├── frontend/             # 档案、资料、会话确认、生成、对比、规则和审计占位
└── ml/                   # 必需视觉语言理解与可选姿态工具接口
```

LLM Agent 负责理解、个性化构思、工具选择与解释。评分以规则库和工具为准，CP-SAT 或同类工具负责已编码约束的合法性；PostgreSQL 保存会话、调用、模型输出、确认及证据采用关系。实现细节和限制详见技术设计，模型通过部署别名配置，不绑定厂商或具体模型 ID。

## 本地启动要点

准备 Python 3.11+、兼容现有前端依赖的 Node LTS 与 npm、PostgreSQL 16+。以下命令从仓库根目录执行；前后端分别使用终端。当前没有一键启动完整产品的容器配置，也不需要启动模型、队列或对象存储来查看骨架。

数据库连接由本地 PostgreSQL 账号配置；如果使用已有实例，只创建独立开发数据库，不在已有业务库执行初始化。脚本按顺序执行一次，遇错立即停止：

```bash
createdb skating_coach
psql -v ON_ERROR_STOP=1 -d skating_coach -f db/schema/001_schema.sql
psql -v ON_ERROR_STOP=1 -d skating_coach -f db/schema/002_seed_minimal.sql
psql -v ON_ERROR_STOP=1 -d skating_coach -f db/schema/003_agent_schema.sql
psql -v ON_ERROR_STOP=1 -d skating_coach -f db/schema/004_seed_agent_demo.sql
psql -v ON_ERROR_STOP=1 -d skating_coach -f db/examples/trace_queries.sql
```

规则种子为 `draft`，分值与数量是未核对的测试数据。可选的 004 使用合成选手、占位媒体和模拟输出，不提供真实媒体、完整节目或正式评分，不能用作推荐依据。初始化快照不具备幂等性，也不是旧业务库迁移脚本。

后端：

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r backend/requirements.txt
cp backend/.env.example backend/.env
cd backend
../.venv/bin/python -m uvicorn app.main:app --reload
```

访问 `http://127.0.0.1:8000/api/health` 检查进程，`http://127.0.0.1:8000/docs` 查看当前接口。健康检查不检测数据库或模型。业务路径规划为 `/api/v1`，目前只有路由模块占位；复制配置后按本机数据库连接调整，模型适配器尚未实现。

前端另开终端，从仓库根目录执行：

```bash
cd frontend
npm install
npm run dev
```

打开 `http://127.0.0.1:5173`；`npm run build` 检查类型并构建静态资源。Agent 和多模态包没有必装运行依赖，可在根目录用 `.venv/bin/python -m pip install -e ./agent -e ./ml` 安装接口包。

## Gitee remote 说明

Gitee 仓库地址占位为 `https://gitee.com/<your-namespace>/skating-coach.git`，使用时将 `<your-namespace>` 替换为自己的用户名或组织名。本次检查时仓库在 `main` 分支，`git remote -v` 未列出远端；本次未添加或修改 remote，也未提交、推送。可用 `git remote -v` 查看本地已有配置；日后自行关联远端时先核对已有地址，避免覆盖已有配置。

## 后续交付顺序

先评审一个目标组别、核对规则与示例，再实现带人工确认的智能理解和编排闭环；随后完成校赛证据链演示，国赛阶段扩展资料覆盖与真实效果评估。首版包含 LLM 与视觉理解，不能把它们降为后期解释插件。具体产品里程碑见 PRD，工程门禁见技术设计。
