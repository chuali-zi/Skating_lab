import { createRouter, createWebHistory } from "vue-router";

// 占位路由：与 frontend/README.md 页面清单一一对应
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", name: "dashboard", component: () => import("../pages/DashboardPage.vue") },
    { path: "/skaters/:id", name: "skater-profile", component: () => import("../pages/SkaterProfilePage.vue") },
    { path: "/assets", name: "assets", component: () => import("../pages/AssetUploadPage.vue") },
    { path: "/programs/generate", name: "program-generate", component: () => import("../pages/ProgramGeneratePage.vue") },
    { path: "/programs/compare", name: "program-compare", component: () => import("../pages/ProgramComparePage.vue") },
    { path: "/rules", name: "rule-library", component: () => import("../pages/RuleLibraryPage.vue") },
    { path: "/admin", name: "admin-audit", component: () => import("../pages/AdminAuditPage.vue") },
  ],
});

export default router;
