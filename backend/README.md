# backend —— API 服务（占位骨架）

FastAPI 应用骨架。当前只包含模块划分与健康检查，**不含业务实现**。

## 模块职责

| 路径 | 职责 |
| --- | --- |
| `app/main.py` | 应用入口（健康检查 `/api/health`） |
| `app/core/config.py` | 环境配置（pydantic-settings 占位） |
| `app/api/v1/skaters.py` | 选手档案、测量、伤病、能力统计 |
| `app/api/v1/assets.py` | 多模态资产上传/标注/ML 任务状态 |
| `app/api/v1/rules.py` | 规则集、元素字典、BV、well-balanced 条款 |
| `app/api/v1/optimizer.py` | 编排生成任务（提交/查询） |
| `app/api/v1/programs.py` | 方案查询、微调、版本链、对比、导出 |
| `app/services/scoring_engine.py` | 期望得分引擎接口占位（BV+GOE+PCS） |
| `app/services/optimizer_service.py` | CP-SAT 求解服务接口占位 |

## 本地启动（开发阶段）

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
uvicorn app.main:app --reload
# API 文档：http://127.0.0.1:8000/docs
```

## 实现入口（TODO）

1. `models/`：按 `db/schema/001_schema.sql` 建立 SQLAlchemy 2 模型 + Alembic 迁移；
2. `services/scoring_engine.py`：实现 §PRD 8.1 期望得分公式，参数全部读库；
3. `services/optimizer_service.py`：引入 OR-Tools CP-SAT，把 `well_balanced_rule` 行编译为求解约束；
4. Celery worker 接入（求解与 ML 任务异步化）。
