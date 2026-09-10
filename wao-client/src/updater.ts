import { reactive } from "vue";
import { check, type Update } from "@tauri-apps/plugin-updater";
import { relaunch } from "@tauri-apps/plugin-process";

export type Phase =
  | "idle"
  | "checking"
  | "current"
  | "available"
  | "downloading"
  | "ready"
  | "installing"
  | "error";

export function createUpdater(enabled = true) {
  const state = reactive({
    phase: "idle" as Phase,
    version: "",
    notes: "",
    progress: null as number | null,
    message: "工作区功能随 Web 持续更新；这里只更新桌面客户端。",
  });
  let update: Update | null = null;
  let busy = false;

  async function find() {
    if (!enabled) {
      state.message = "开发宿主通过源码迭代；请在正式客户端检查安装更新。";
      return;
    }
    if (busy || state.phase === "ready") return;
    busy = true;
    state.phase = "checking";
    state.message = "正在检查新版本…";
    try {
      await update?.close();
      update = await check({ timeout: 15000 });
      state.version = update?.version ?? "";
      state.notes = update?.body ?? "";
      state.phase = update ? "available" : "current";
      state.message = update
        ? `新版本 ${update.version} 已准备好`
        : "你正在使用最新版本";
    } catch {
      update = null;
      state.phase = "error";
      state.message =
        "暂时无法检查更新，请检查网络后重试。你可以继续使用当前版本。";
    } finally {
      busy = false;
    }
  }

  async function download() {
    if (busy || !update || state.phase !== "available") return;
    busy = true;
    state.phase = "downloading";
    state.progress = null;
    state.message = "正在下载并验证更新，你可以返回工作区继续使用。";
    let total = 0;
    let received = 0;
    try {
      await update.download((event) => {
        if (event.event === "Started") total = event.data.contentLength ?? 0;
        if (event.event === "Progress") received += event.data.chunkLength;
        state.progress =
          total > 0 ? Math.min(99, Math.round((received / total) * 100)) : null;
      });
      // Download resolves only after updater verifies the signature.
      state.phase = "ready";
      state.progress = 100;
      state.message = "更新已下载并验证。保存工作内容后，即可安装。";
    } catch {
      state.phase = "error";
      state.message = "下载或签名验证失败，当前版本保持不变。请重新检查更新。";
    } finally {
      busy = false;
    }
  }

  async function install() {
    if (busy || !update || state.phase !== "ready") return;
    busy = true;
    state.phase = "installing";
    state.message = "正在安装更新…";
    try {
      await update.install();
      await relaunch();
    } catch {
      state.phase = "error";
      state.message = "安装未完成，请重新检查更新或下载完整安装包覆盖安装。";
    } finally {
      busy = false;
    }
  }

  return { state, find, download, install };
}
