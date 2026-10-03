import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";
import vue from "@vitejs/plugin-vue";
export default defineConfig({
  root: fileURLToPath(new URL(".", import.meta.url)),
  base: "./",
  plugins: [vue()],
  build: {
    outDir: fileURLToPath(new URL("../../dist/camp", import.meta.url)),
    emptyOutDir: true,
    sourcemap: false,
  },
  server: { host: "127.0.0.1", port: 5276, strictPort: true },
  test: { include: ["**/*.test.ts"], environment: "node", maxWorkers: 2 },
});
