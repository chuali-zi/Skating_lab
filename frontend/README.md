# frontend：产品页面骨架

Vue、TypeScript 与 Vite 的页面占位，尚无业务请求与真实交互。产品侧突出资料理解、人工确认、个性化取舍与可解释报告，不向选手堆叠技术实现术语。

| 页面 | 路由 | 后续职责 |
| --- | --- | --- |
| `DashboardPage.vue` | `/` | 选手、方案、待确认事项与处理状态 |
| `SkaterProfilePage.vue` | `/skaters/:id` | 能力、训练限制、样本和证据来源 |
| `AssetUploadPage.vue` | `/assets` | 混合资料、使用授权、片段标注和支持范围 |
| `AgentSessionPage.vue` | `/sessions/:id` | 会话、澄清、观察出处、关键事实确认和修改 |
| `ProgramGeneratePage.vue` | `/programs/generate` | 目标、偏好与资料选择；发起智能教练会话 |
| `ProgramComparePage.vue` | `/programs/compare` | 方案取舍、预估、依据、人工微调与定稿 |
| `RuleLibraryPage.vue` | `/rules` | 规则出处、版本、适用范围、草稿与发布状态 |
| `AdminAuditPage.vue` | `/admin` | 有权限人员查看会话、工具、模型输出摘要与审计 |

启动：仓库根目录进入 `frontend`，执行 `npm install`、`npm run dev`。`npm run build` 执行类型检查和构建。未来业务 API 前缀为 `/api/v1`，健康检查仍为 `/api/health`；当前只有健康检查实现。

TODO：接入会话状态与取消、复核修订号、微调后重新校验；等待过程不暴露模型内部推理。解释引用应跳转到有权限的原始片段和规则出处，演示回放标明来源与时间。产品验收见 [PRD](../docs/PRD.md)，调用关系见 [技术设计](../docs/technical-design.md)。
