# frontend —— 前端（占位骨架）

Vue 3 + TypeScript + Vite + Element Plus。当前仅包含页面与路由占位，**未实现业务界面**。

## 页面清单（src/pages）

| 页面 | 路由 | 职责 |
| --- | --- | --- |
| `DashboardPage.vue` | `/` | 总览看板（选手数、方案数、任务状态） |
| `SkaterProfilePage.vue` | `/skaters/:id` | 选手档案：基本信息、测量、伤病风险标签、能力总览 |
| `AssetUploadPage.vue` | `/assets` | 多模态资料上传与时间轴标注（P0 人工标注主战场） |
| `ProgramGeneratePage.vue` | `/programs/generate` | 选择选手+目标赛事+节目类型+风险档位，提交生成任务 |
| `ProgramComparePage.vue` | `/programs/compare` | 多方案对比：期望 TES/PCS/总分、逐元素拆解、约束报告 |
| `RuleLibraryPage.vue` | `/rules` | 规则集/元素字典/BV/well-balanced 条款管理（"规则即数据"演示页） |
| `AdminAuditPage.vue` | `/admin` | 用户角色、审计日志、数据字典 |

## 本地启动（开发阶段）

```bash
cd frontend
npm install
npm run dev   # http://127.0.0.1:5173
```

## 实现入口（TODO）

1. `src/api/`：基于 OpenAPI 生成或手写 API client（后端 `/api` 前缀）；
2. 图表：方案对比用 ECharts（期望分堆叠图 + 风险雷达图）；
3. 视频标注：时间轴组件（P0）→ 接入 `ml_task` 进度展示（P1）。
