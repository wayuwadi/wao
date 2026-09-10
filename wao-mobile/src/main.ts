import { createApp } from "vue";
import { createRouter, createWebHashHistory } from "vue-router";
import App from "./App.vue";
import WorkspaceView from "./views/WorkspaceView.vue";
import "./style.css";
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", redirect: "/today" },
    ...["today", "tasks", "calendar", "notes"].map((tab) => ({
      path: `/${tab}`,
      component: WorkspaceView,
      props: { tab },
    })),
  ],
});
createApp(App).use(router).mount("#app");
