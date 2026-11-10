import { createApp } from "vue";
import App from "./App.vue";
import router from "./router";

// TODO: 注册 Element Plus / Pinia
createApp(App).use(router).mount("#app");
