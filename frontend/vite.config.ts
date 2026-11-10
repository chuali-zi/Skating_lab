import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// TODO: dev server 代理 /api -> http://127.0.0.1:8000
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      "/api": "http://127.0.0.1:8000",
    },
  },
});
