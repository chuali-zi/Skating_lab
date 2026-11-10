# db —— 数据库 Schema 与种子数据

本目录是数据库设计的"可执行快照"，与 [docs/database-design.md](../docs/database-design.md) 一一对应。

## 文件清单

| 文件 | 说明 |
| --- | --- |
| `schema/001_schema.sql` | 全量 DDL：8 个业务域 32 张表（PK/FK/CHECK/索引/触发器/COMMENT） |
| `schema/002_seed_minimal.sql` | 最小种子数据（角色、元素字典、示意规则集与 well-balanced 条款） |

> ⚠ 种子数据中的 Base Value / 参数 / 数量限制均为**演示示意值**，正式数据以当季 ISU 官方通讯为准，需双人核对录入。

## 本地初始化（PostgreSQL 16）

```bash
createdb skating_coach
psql -d skating_coach -f db/schema/001_schema.sql
psql -d skating_coach -f db/schema/002_seed_minimal.sql
```

或使用 Docker：

```bash
docker run -d --name skating-pg -e POSTGRES_PASSWORD=skating -e POSTGRES_DB=skating_coach -p 5432:5432 postgres:16
```

## 后续演进约定

- 开发期使用 Alembic（见 `backend/`）管理增量迁移；
- `db/schema/` 始终保持"当前全量快照"角色：每次结构变更后同步更新本目录与设计文档；
- 大表（`pose_frame_feature` / `audit_log` / `element_attempt_log`）的分区策略在物理设计章节说明，MVP 暂不分区。
