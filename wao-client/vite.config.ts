import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  clearScreen: false,
  server: { host: "localhost", port: 1430, strictPort: true },
  envPrefix: ["VITE_", "TAURI_"],
  build: { target: ["chrome105", "safari14"] },
});
