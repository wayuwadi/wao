<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { invoke, isTauri } from "@tauri-apps/api/core";
import { getVersion } from "@tauri-apps/api/app";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { getCurrentWindow } from "@tauri-apps/api/window";
import { createUpdater } from "./updater";
import packageInfo from "../package.json";

const native = isTauri();
const platform = navigator.platform.includes("Mac") ? "macOS" : "Windows";
const version = ref(packageInfo.version);
const connection = ref<"loading" | "ready" | "error">("loading");
const online = ref(navigator.onLine);
const panel = ref(false);
const confirm = ref<"reload" | "quit" | "install" | null>(null);
const error = ref("");
const downloadMessage = ref("");
const updater = createUpdater(!import.meta.env.DEV);
const state = updater.state;
const modal = computed(() => confirm.value !== null);
const modalElement = ref<HTMLElement | null>(null);
let previousFocus: HTMLElement | null = null;
watch(confirm, async (value) => {
  if (value) {
    previousFocus =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    await nextTick();
    modalElement.value?.querySelector<HTMLButtonElement>("button")?.focus();
  } else previousFocus?.focus();
});
const status = computed(() =>
  !online.value
    ? "网络已断开"
    : connection.value === "ready"
      ? ""
      : connection.value === "error"
        ? "连接待恢复"
        : "正在连接",
);
const confirmTitle = computed(() =>
  confirm.value === "reload"
    ? "刷新工作区？"
    : confirm.value === "quit"
      ? "退出 WAO？"
      : "安装并重新打开 WAO？",
);
const toolbar = ref({ ready: false, chat: '0', notifications: '0' });
const toolbarActions = [
  { id: 'new-task', label: '新建任务', icon: 'M9 12l2 2 4-4', circle: true },
  { id: 'new-note', label: '新建小记', icon: 'M5 4h14v12l-4 4H5zM14 20v-5h5' },
  { id: 'theme', label: '切换主题', icon: 'M12 3v18', circle: true },
  { id: 'shortcuts', label: '快捷键', icon: 'M3 6h18v12H3zM6 9h1m3 0h1m3 0h1m3 0h1M6 12h1m3 0h1m3 0h1m3 0h1M8 15h8' },
  { id: 'chat', label: '聊天', icon: 'M20 11a8 8 0 0 1-8 8H5l-3 3V11a9 9 0 0 1 18 0z' },
  { id: 'notifications', label: '通知中心', icon: 'M5 16h14l-2-3V9a5 5 0 0 0-10 0v4zM10 20h4' },
];
async function quickAction(name: string) {
  if (panel.value) await togglePanel(false);
  await action(name);
}
const cleanup: UnlistenFn[] = [];
let updateTimer: ReturnType<typeof setInterval> | undefined;
let timer: ReturnType<typeof setTimeout> | undefined;

async function startUpdate() {
  if (state.phase === "error") await updater.find();
  if (state.phase === "available") await updater.download();
  if (state.phase === "ready") await ask("install");
}
async function action(name: string) {
  if (!native) return;
  try {
    await invoke("workspace_action", { action: name });
    error.value = "";
  } catch {
    error.value = "操作暂未完成，请重试。";
  }
}
async function visibility() {
  await action(panel.value || modal.value ? "hide" : "show");
}
async function togglePanel(value: boolean) {
  panel.value = value;
  await visibility();
}
async function ask(kind: "reload" | "quit" | "install") {
  confirm.value = kind;
  await visibility();
}
async function cancel() {
  confirm.value = null;
  await visibility();
}
async function accept() {
  const kind = confirm.value;
  confirm.value = null;
  if (kind === "quit") {
    if (native) await invoke("quit_client");
    return;
  }
  if (kind === "install") {
    await updater.install();
    await visibility();
    return;
  }
  await visibility();
  await reload();
}
function armTimeout() {
  clearTimeout(timer);
  timer = setTimeout(() => {
    if (connection.value === "loading") connection.value = "error";
  }, 20000);
}
async function connect() {
  connection.value = "loading";
  error.value = "";
  armTimeout();
  if (!native) {
    connection.value = "error";
    return;
  }
  try {
    await invoke("attach_workspace");
    await visibility();
  } catch {
    connection.value = "error";
    error.value = "工作区未能打开，请重试。";
  }
}
async function reload() {
  await action("reload");
}
async function retry() {
  await connect();
  await reload();
}
function networkChanged() {
  online.value = navigator.onLine;
}
async function windowAction(name: "minimize" | "toggleMaximize" | "hide") {
  if (native) await getCurrentWindow()[name]();
}
function keydown(event: KeyboardEvent) {
  if (event.key === "Tab" && confirm.value) {
    const buttons = modalElement.value?.querySelectorAll<HTMLButtonElement>(
      "button:not(:disabled)",
    );
    if (buttons?.length) {
      const first = buttons[0],
        last = buttons[buttons.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last?.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first?.focus();
      }
    }
  }
  if (event.key === "Escape" && confirm.value) {
    void cancel();
    return;
  }
  if (event.key === "Escape" && panel.value) void togglePanel(false);
}

onMounted(async () => {
  window.addEventListener("online", networkChanged);
  window.addEventListener("offline", networkChanged);
  window.addEventListener("keydown", keydown);
  if (native) {
    version.value = await getVersion();
    void updater.find();
    updateTimer = setInterval(() => void updater.find(), 30 * 60 * 1000);
    cleanup.push(
      await listen<{ ready: boolean; chat: string; notifications: string }>("toolbar-state", (event) => { toolbar.value = event.payload; }),
      await listen<string>("workspace-state", (event) => {
        connection.value = event.payload === "ready" ? "ready" : "loading";
        if (connection.value === "ready") error.value = "";
        if (connection.value === "loading") armTimeout();
        else clearTimeout(timer);
      }),
    );
    cleanup.push(
      await listen<string>("shell-action", (event) => {
        if (event.payload === "update") {
          void togglePanel(true);
          void updater.find();
        }
        if (event.payload === "quit") void ask("quit");
        if (event.payload === "workspace") { confirm.value = null; void togglePanel(false); }
      }),
    );
    cleanup.push(
      await listen<string>("workspace-download", (event) => {
        downloadMessage.value = event.payload;
      }),
    );
    await getCurrentWindow().show();
  }
  await connect();
});
onUnmounted(() => {
  clearInterval(updateTimer);
  cleanup.forEach((fn) => fn());
  clearTimeout(timer);
  window.removeEventListener("online", networkChanged);
  window.removeEventListener("offline", networkChanged);
  window.removeEventListener("keydown", keydown);
});
</script>

<template>
  <div class="client">
    <header class="toolbar">
      <div class="brand" data-tauri-drag-region>
        <span class="edition" data-tauri-drag-region>WAO</span>
      </div>
      <div class="divider"></div>
      <nav class="history" aria-label="工作区导航">
        <button
          class="icon-button"
          title="后退"
          aria-label="后退"
          @click="action('back')"
        >
          <svg viewBox="0 0 24 24"><path d="m14 6-6 6 6 6" /></svg>
        </button>
        <button
          class="icon-button"
          title="前进"
          aria-label="前进"
          @click="action('forward')"
        >
          <svg viewBox="0 0 24 24"><path d="m10 6 6 6-6 6" /></svg>
        </button>
        <button
          class="icon-button"
          title="刷新工作区"
          aria-label="刷新工作区"
          @click="reload"
        >
          <svg viewBox="0 0 24 24">
            <path d="M20 7v5h-5M19 12a7 7 0 1 0-1.8 4.7M20 12l-2-5" />
          </svg>
        </button>
      </nav>
      <div class="drag-space" data-tauri-drag-region></div>
      <nav class="quick-actions" aria-label="工作区快捷操作">
        <button v-for="item in toolbarActions" :key="item.id" :data-action="item.id"
          :disabled="!toolbar.ready" :title="item.label" :aria-label="item.label"
          @click="quickAction(item.id)">
          <svg viewBox="0 0 24 24" aria-hidden="true"><circle v-if="item.circle" cx="12" cy="12" r="9"/><path :d="item.icon"/></svg>
          <span v-if="item.id === 'new-task' || item.id === 'new-note'" class="quick-label">{{ item.label }}</span>
          <small v-if="item.id === 'chat' && toolbar.chat !== '0'">{{ toolbar.chat }}</small>
          <small v-if="item.id === 'notifications' && toolbar.notifications !== '0'">{{ toolbar.notifications }}</small>
        </button>
      </nav>
      <span
        v-if="status"
        class="connection"
        :class="{ offline: !online || connection === 'error' }"
        ><i></i>{{ status }}</span
      >
      <span
        v-if="downloadMessage"
        class="download-status"
        role="status"
        :title="downloadMessage"
        >{{ downloadMessage }}</span
      >
      <button v-if="connection === 'error'" class="retry-small" @click="retry">
        重试连接
      </button>
      <button class="pet-button" @click="action('pet')" title="让宠物到桌面帮你传话"><span>桌面宠物</span></button>
      <button
        v-if="['available', 'downloading', 'ready', 'installing'].includes(state.phase) || (state.phase === 'error' && state.version)"
        class="update-button"
        :disabled="['downloading', 'installing'].includes(state.phase)"
        :title="state.message"
        :class="{ active: panel }"
        @click="startUpdate"
      >
        <svg viewBox="0 0 24 24">
          <path d="M12 16V4m-4 4 4-4 4 4M5 15v5h14v-5" /></svg
        ><span>{{ state.phase === 'downloading' ? '正在更新…' : state.phase === 'error' ? '重试更新' : '更新客户端' }}</span
        ><i
          v-if="['available', 'ready'].includes(state.phase)"
          class="update-dot"
        ></i>
      </button>
      <div v-if="platform !== 'macOS'" class="divider"></div>
      <div v-if="platform !== 'macOS'" class="window-controls">
        <button
          class="icon-button"
          aria-label="最小化"
          title="最小化"
          @click="windowAction('minimize')"
        >
          <svg viewBox="0 0 24 24"><path d="M6 12h12" /></svg>
        </button>
        <button
          class="icon-button"
          aria-label="最大化或还原"
          title="最大化或还原"
          @click="windowAction('toggleMaximize')"
        >
          <svg viewBox="0 0 24 24">
            <rect x="6" y="6" width="12" height="12" rx="1" />
          </svg>
        </button>
        <button
          class="icon-button close"
          aria-label="收起到托盘"
          title="收起到托盘，保留工作区"
          @click="windowAction('hide')"
        >
          <svg viewBox="0 0 24 24"><path d="m7 7 10 10M7 17 17 7" /></svg>
        </button>
      </div>
    </header>

    <main class="surface">
      <section v-if="!panel" class="connection-page" aria-live="polite">
        <div class="hero-mark">W</div>
        <p class="eyebrow">YOUR EVERYDAY WORKSPACE</p>
        <h1>
          {{
            connection === "error" ? "工作区暂时无法连接" : "正在打开你的 WAO"
          }}
        </h1>
        <p>
          {{
            connection === "error"
              ? "检查网络后再试一次。已经打开的工作内容会保留。"
              : "你的项目、任务与灵感，都在这里。"
          }}
        </p>
        <button v-if="connection === 'error'" class="primary" @click="retry">
          重新连接
        </button>
        <div v-else class="loader"></div>
        <p v-if="!native" class="notice">
          这是界面预览。请通过 desktop:dev 启动真实客户端。
        </p>
      </section>

      <section v-if="panel" class="settings">
        <div class="settings-top">
          <div>
            <p class="eyebrow">WAO DESKTOP</p>
            <h1>让工作，随时就绪。</h1>
            <p>熟悉的 WAO，专属于你的桌面空间。</p>
          </div>
          <button class="secondary" @click="togglePanel(false)">
            返回工作区 <span>↗</span>
          </button>
        </div>
        <div class="settings-grid">
          <article class="version-card">
            <div class="version-heading">
              <div class="hero-mark small">W</div>
              <div>
                <h2>WAO 桌面客户端</h2>
                <span>当前版本 {{ version }}</span>
              </div>
              <span class="platform-tag">{{ platform }}</span>
            </div>
            <div class="update-state" aria-live="polite">
              <span class="pill" :class="state.phase">{{
                state.phase === "ready"
                  ? "准备安装"
                  : state.phase === "available"
                    ? "有新版本"
                    : state.phase === "error"
                      ? "需要重试"
                      : state.phase === "current"
                        ? "已是最新"
                        : "持续更新"
              }}</span>
              <h3>{{ state.message }}</h3>
              <p v-if="state.notes" class="notes">{{ state.notes }}</p>
              <progress
                v-if="state.phase === 'downloading'"
                :value="state.progress ?? undefined"
                max="100"
              ></progress
              ><small v-if="state.phase === 'downloading'">{{
                state.progress === null ? "正在下载…" : `${state.progress}%`
              }}</small>
            </div>
            <div class="update-actions">
              <button
                v-if="state.phase === 'available'"
                class="primary"
                @click="updater.download"
              >
                下载 {{ state.version }}</button
              ><button
                v-else-if="state.phase === 'ready'"
                class="primary"
                @click="ask('install')"
              >
                安装并重新打开</button
              ><button
                v-else
                class="primary"
                :disabled="
                  ['checking', 'downloading', 'installing'].includes(
                    state.phase,
                  )
                "
                @click="updater.find"
              >
                {{
                  state.phase === "checking"
                    ? "正在检查…"
                    : state.phase === "downloading"
                      ? "正在下载…"
                      : state.phase === "installing"
                        ? "正在安装…"
                        : "检查更新"
                }}</button
              ><span>更新前会验证发布签名</span>
            </div>
          </article>
          <aside class="help-card">
            <span class="spark">✦</span>
            <h2>保持专注，<br />其余交给 WAO。</h2>
            <p>
              {{
                platform === "macOS"
                  ? "关闭窗口后，可从 Dock 或菜单栏重新打开，回到刚才的工作内容。"
                  : "关闭窗口会收起到托盘。再次打开时，回到刚才的工作内容。"
              }}
            </p>
            <div class="help-rule"></div>
            <p>Web 新功能无需重新安装。准备好后，刷新工作区即可使用。</p>
            <button class="text-button" @click="action('browser')">
              在浏览器打开 WAO ↗
            </button>
          </aside>
        </div>
        <div class="settings-foot">
          <span>一个工作区，连接想法与行动。</span
          ><button class="text-button" @click="ask('quit')">退出 WAO</button>
        </div>
      </section>
      <div v-if="state.phase === 'error' && state.version" class="error-toast" role="alert">{{ state.message }}</div>
      <div v-if="error" class="error-toast" role="alert">{{ error }}</div>
      <div v-if="confirm" class="modal-backdrop">
        <section
          class="modal"
          ref="modalElement"
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="confirm-title"
        >
          <span class="modal-symbol">↗</span>
          <h2 id="confirm-title">{{ confirmTitle }}</h2>
          <p>
            请先保存工作区中的编辑内容，未保存的输入可能丢失。{{
              confirm === "quit"
                ? "如果只是暂时离开，可以使用窗口关闭按钮收起窗口。"
                : "你也可以取消，回到工作区继续编辑。"
            }}
          </p>
          <div class="modal-actions">
            <button class="secondary" autofocus @click="cancel">
              返回继续编辑</button
            ><button class="primary" @click="accept">已保存，继续</button>
          </div>
        </section>
      </div>
    </main>
  </div>
</template>
