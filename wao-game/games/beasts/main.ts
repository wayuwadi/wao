import { createApp } from "vue";
import App from "./App.vue";
createApp(App).mount("#app");

if (import.meta.env.DEV && new URLSearchParams(location.search).has("qa"))
  void import("./performance-preview");
