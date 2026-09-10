import { computed, ref } from "vue";
import { Capacitor } from "@capacitor/core";
import {
  downloadUrl,
  fetchRelease,
  installedVersion,
  newer,
  openDownload,
  type MobileRelease,
  type Platform,
} from "../api/updates";
const HOUR = 3600000;
export function createUpdateStore(
  deps = {
    fetch: fetchRelease,
    installed: installedVersion,
    open: openDownload,
    platform: () => Capacitor.getPlatform(),
    now: () => Date.now(),
    storage: localStorage,
  },
) {
  const version = ref(""),
    release = ref<MobileRelease | null>(null),
    checking = ref(false),
    error = ref(""),
    checkedAt = ref(0),
    modal = ref(false),
    opening = ref(false);
  let lastAttempt: number | null = null,
    inflight: Promise<void> | null = null,
    manualWanted = false,
    dismissed: { version: string; until: number } | null = null;
  const platform = computed<Platform>(() =>
    deps.platform() === "ios" ? "ios" : "android",
  );
  const available = computed(
    () =>
      !!version.value &&
      !!release.value?.files[platform.value] &&
      newer(release.value.version, version.value),
  );
  const status = computed(() =>
    checking.value
      ? "正在检查更新…"
      : error.value ||
        (available.value
          ? `发现新版本 ${release.value!.version}`
          : checkedAt.value
            ? "当前已是最新版本"
            : "检查新版本，让日常更顺手。"),
  );
  function muted(v: string) {
    try {
      const saved = JSON.parse(
        deps.storage.getItem("wao-mobile:update-reminder:v1") || "null",
      );
      if (
        saved &&
        typeof saved.version === "string" &&
        typeof saved.until === "number"
      )
        dismissed = saved;
    } catch {}
    return dismissed?.version === v && dismissed.until > deps.now();
  }
  async function check(manual = false) {
    manualWanted ||= manual;
    if (inflight) return inflight;
    if (!manual && lastAttempt !== null && deps.now() - lastAttempt < 6 * HOUR)
      return;
    lastAttempt = deps.now();
    checking.value = true;
    error.value = "";
    inflight = (async () => {
      try {
        const [current, latest] = await Promise.all([
          deps.installed(),
          deps.fetch(),
        ]);
        version.value = current;
        release.value = latest;
        checkedAt.value = deps.now();
        if (available.value && (manualWanted || !muted(latest.version)))
          modal.value = true;
        if (!available.value) modal.value = false;
        if (!latest.files[platform.value])
          error.value = "此平台暂时没有可用的更新包，请稍后重试。";
      } catch {
        lastAttempt = deps.now() - 6 * HOUR + 60000;
        error.value = "检查更新失败，请检查网络后重试。";
      } finally {
        checking.value = false;
        inflight = null;
        manualWanted = false;
      }
    })();
    return inflight;
  }
  async function initialize() {
    // Installed version remains visible even when the download site is offline.
    try {
      version.value = await deps.installed();
    } catch {
      error.value = "无法读取安装版本，请重启应用后重试。";
    }
    await check();
  }
  function later() {
    modal.value = false;
    if (release.value) {
      dismissed = {
        version: release.value.version,
        until: deps.now() + 24 * HOUR,
      };
      try {
        deps.storage.setItem(
          "wao-mobile:update-reminder:v1",
          JSON.stringify(dismissed),
        );
      } catch {}
    }
  }
  async function download() {
    if (!available.value || !release.value || opening.value) return;
    opening.value = true;
    error.value = "";
    try {
      await deps.open(downloadUrl(release.value, platform.value));
      later();
    } catch (e) {
      error.value = e instanceof Error ? e.message : "无法打开下载，请稍后重试";
    } finally {
      opening.value = false;
    }
  }
  return {
    version,
    release,
    checking,
    error,
    checkedAt,
    modal,
    opening,
    platform,
    available,
    status,
    check,
    initialize,
    later,
    download,
  };
}
export const updates = createUpdateStore();
