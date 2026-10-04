import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
import { previewServer } from "./tests/preview-server.ts";
const localApi = process.env.WAO_MOBILE_LOCAL_API;
if (localApi && !/^http:\/\/(?:127\.0\.0\.1|localhost):\d+$/.test(localApi))
  throw new Error("本地验收代理只能指向回环地址");
export default defineConfig(({ mode }) => ({
  plugins: [vue(), ...(mode === "demo" ? [previewServer()] : [])],
  server: {
    port: 5178,
    strictPort: true,
    proxy: {
      "/downloads/mobile": {
        target: "https://wao.wayuwa.cn",
        changeOrigin: true,
      },
      "/api": {
        target: localApi || "https://wao.wayuwa.cn",
        changeOrigin: true,
        cookieDomainRewrite: "",
        cookiePathRewrite: "/",
      },
    },
  },
  test: { environment: "jsdom", restoreMocks: true },
}));
