# Skating Coach · 花样滑冰 AI 教练 / 个性化动作编排系统

输入选手的极端多模态数据（训练视频、照片、表单/自述），结合其真实能力与目标赛事规则，通过**可解释的约束优化（CP-SAT）**生成期望得分最大化的花样滑冰编排（元素选择 + 顺序），并给出得分拆解、风险与备选方案。

- 目标赛事：校赛「计算机数据库设计大赛」→ 国赛「中国大学生计算机设计大赛」
- 核心文档：[docs/PRD.md](docs/PRD.md) · [docs/database-design.md](docs/database-design.md)

## 核心理念

1. **规则即数据**：ISU Base Value / GOE 参数 / well-balanced 条款全部存库（`ruleset_version` 等表），赛季更新 = 插入新数据行，不改代码；
2. **可解释优化**：编排由约束规划求解（决策变量 = 槽位×候选元素，硬约束 = 规则库条款 + 伤病禁忌，目标 = 期望 TES + PCS − λ·风险），每个方案附逐条约束检查报告，而非黑盒生成；
3. **证据链可追溯**：视频 → 姿态特征 → 能力证据 → 统计快照 → 编排决策，一条 SQL 可追溯。

## 目录结构

```
skating-coach/
├── docs/                 # PRD 与数据库设计文档
├── db/
│   ├── README.md
│   └── schema/           # PostgreSQL DDL（001 全量结构 / 002 最小种子数据）
├── backend/              # FastAPI 占位骨架（skaters/assets/rules/optimizer/programs）
├── frontend/             # Vue 3 占位骨架（档案/上传/生成/对比/规则库/后台）
└── ml/                   # 多模态流水线占位包（pose / features / llm）
```

当前仓库为**骨架阶段**：文档完整、SQL 可执行，应用代码只有占位接口与 TODO。

## 本地开发如何开始

环境要求：Python 3.11+、Node 18+、PostgreSQL 16（Docker 即可）。

```bash
# 1) 数据库
docker run -d --name skating-pg -e POSTGRES_PASSWORD=skating -e POSTGRES_DB=skating_coach -p 5432:5432 postgres:16
psql -d skating_coach -h localhost -U postgres -f db/schema/001_schema.sql
psql -d skating_coach -h localhost -U postgres -f db/schema/002_seed_minimal.sql

# 2) 后端
cd backend && python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt && cp .env.example .env
uvicorn app.main:app --reload          # http://127.0.0.1:8000/docs

# 3) 前端
cd frontend && npm install && npm run dev   # http://127.0.0.1:5173
```

## 推送到你自己的 Gitee 仓库

远端仓库尚未创建。你先在 Gitee 上新建空仓库（不要勾选初始化 README），然后把下面占位地址替换为你的真实地址：

```bash
git remote add origin https://gitee.com/<your-namespace>/skating-coach.git
git push -u origin main
```

> 把 `<your-namespace>` 换成你的 Gitee 用户名/组织名即可。若仓库需私有，推送时按提示输入 Gitee 账号或配置 SSH key。

## 里程碑速览

阶段 0 设计定稿 → 阶段 1 MVP（校赛：库表 + 规则引擎 + CP-SAT 编排 + 方案对比）→ 阶段 2 校赛冲刺 → 阶段 3 多模态（姿态估计接入）→ 阶段 4 国赛完善（LLM 助手 + 产品打磨）。详见 [PRD §13](docs/PRD.md)。
