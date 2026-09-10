import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import { previewServer } from "./tests/preview-server.ts";
export default defineConfig(({ mode }) => ({
  plugins: [vue(), ...(mode === "demo" ? [previewServer()] : [])],
  server: {
    port: 5178,
    strictPort: true,
    proxy: {
      "/api": {
        target: "https://wao.wayuwa.cn",
        changeOrigin: true,
        cookieDomainRewrite: "",
        cookiePathRewrite: "/",
      },
    },
  },
  test: { environment: "jsdom", restoreMocks: true },
}));
