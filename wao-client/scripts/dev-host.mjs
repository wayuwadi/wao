import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { spawn } from "node:child_process";

// The optional CI-built development host lets Windows frontend work use Vite HMR
// without invoking a compiler on machines whose approved toolchain is not ready.
const executable = resolve(
  process.env.WAO_CLIENT_DEV_HOST || "dev-host/wao-client.exe",
);
if (process.platform !== "win32" || !existsSync(executable)) {
  console.error(
    "请从 GitHub Actions 下载 WAO-client-dev-host-windows，将 wao-client.exe 放入 wao-client/dev-host/，然后重试。macOS 请使用 desktop:dev。",
  );
  process.exit(1);
}
let stopped = false;
let host;
const vite = spawn(process.execPath, ["node_modules/vite/bin/vite.js"], {
  stdio: "inherit",
});
function stop(code = 0) {
  if (stopped) return;
  stopped = true;
  host?.kill();
  vite.kill();
  process.exit(code);
}
process.on("SIGINT", () => stop());
process.on("SIGTERM", () => stop());
vite.on("error", () => stop(1));
vite.on("exit", (code) => stop(code ?? 1));
for (let attempt = 0; attempt < 60 && !stopped; attempt++) {
  await new Promise((resolve) => setTimeout(resolve, 250));
  try {
    const response = await fetch("http://localhost:1430/", {
      signal: AbortSignal.timeout(500),
    });
    if (!response.ok) continue;
    host = spawn(executable, [], { stdio: "inherit" });
    host.on("error", (error) => {
      console.error(error.message);
      stop(1);
    });
    host.on("exit", (code) => stop(code ?? 0));
    break;
  } catch {
    /* Wait for this Vite child to bind its strict port. */
  }
}
if (!host) stop(1);
