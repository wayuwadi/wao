import type { CapacitorConfig } from "@capacitor/cli";
const config: CapacitorConfig = {
  appId: "cn.wayuwa.wao",
  appName: "WAO",
  webDir: "dist",
  // Ship bundled UI. Live reload is provided by the CLI only, never a release server.url.
  server: { androidScheme: "https" },
  android: { allowMixedContent: false },
  plugins: { Keyboard: { resize: "native", resizeOnFullScreen: true } },
  ios: { contentInset: "automatic", preferredContentMode: "mobile" },
};
export default config;
