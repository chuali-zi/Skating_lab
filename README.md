# Skating Coach：花样滑冰跳跃配置助手

面向花样滑冰选手，以文字和表单填写现有跳跃难度及允许组合，提供三个核心功能：根据适用规则推荐 BV 最大化跳跃配置、检查自编配置、针对具体失误调整剩余跳跃以减少 BV 损失。用户自述直接作为输入，不要求先提供视频、完整画像或教练确认。

首版聚焦跳跃配置，最多附带旋转占位；用户画像和视频／姿态辅助留作后续增强，多组别暂不展开。项目用于数据库设计或计算机设计类比赛展示时，也围绕这三个核心功能展开。

**当前为文档与骨架阶段。** 可初始化数据库，后端有健康检查，前端有页面占位；尚无真实模型接入、智能体运行时、评分或求解实现。设计中的验收目标不等于已取得的效果。

## 文档入口

- [产品需求](docs/PRD.md)：用户、场景、产品能力、优先级与验收边界，保持纯产品表述。
- [技术设计](docs/technical-design.md)：旧版技术设想，尚未按当前产品范围调整；其中多模态、人工确认等要求不作为首版门槛。
- [数据库设计](docs/database-design.md)：保留既有八域，扩展为九域四十四表，含数据字典、ER、约束分工和查询。
- [数据库初始化](db/README.md)：可执行结构、示意种子及血缘查询。

当前产品需求以 `docs/PRD.md` v0.4 为准，本轮仅更新 Markdown，仓库内旧版 PRD.pdf 不作为本版需求依据。技术设计、数据库设计及程序骨架尚未按本轮产品需求调整，待产品评审后另行讨论和对齐实现。

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
└── ml/                   # 既有视觉与姿态接口骨架，非当前首版依赖
```

上述目录保留既有骨架；具体实现选择待本版产品需求评审后讨论。当前技术文档中的模型、求解工具和数据结构均不代表本版实现已经确定。

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

先评审 BV 最大化推荐、自编配置检查和失误减损预案，确定一个明确的首发规则范围，再讨论实现。基础工具可用后再评估用户画像与可选姿态辅助；音乐、步法和完整节目编排不在首版范围，多组别暂不安排。当前验收目标以 PRD 为准。
