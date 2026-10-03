<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { childEvent, envelope, type ParentCommand } from "../protocol/host";
import {
  FILE_LIMIT,
  exportMemory,
  parseMemory,
  summary,
  type Backup,
  type MemoryRecord,
} from "./memory";
import { useGameMemory } from "./useGameMemory";
import { games, clonePayload, type GameId } from "./registry";

const props = withDefaults(
  defineProps<{
    active: boolean;
    gameId?: GameId;
    theme?: "light" | "dark";
    source?: string;
  }>(),
  { theme: "light", gameId: "dino" },
);
const emit = defineEmits<{
  leave: [];
  immersion: [active: boolean];
  memoryPanel: [active: boolean];
}>();
const memories = {
  dino: useGameMemory("dino"),
  rift: useGameMemory("rift"),
  starfield: useGameMemory("starfield"),
  beasts: useGameMemory("beasts"),
  camp: useGameMemory("camp"),
};
const memory = memories[props.gameId],
  game = games[props.gameId];
const frame = ref<HTMLIFrameElement>(),
  source = ref(""),
  instance = ref(crypto.randomUUID());
const loadError = ref(""),
  loaded = ref(false),
  panel = ref(false),
  immersive = ref(false),
  section = ref<HTMLElement>();
const fileInput = ref<HTMLInputElement>(),
  feedback = ref(""),
  pending = ref<{
    record: MemoryRecord;
    additional?: {
      record: MemoryRecord;
      original?: MemoryRecord;
      expected: number;
    }[];
    original?: MemoryRecord;
    expected: number;
    toLocal: boolean;
    title: string;
    readOnly?: boolean;
  }>();
const backups = ref<Backup[]>([]),
  busy = ref(false);
const exportUrl = ref(""),
  exportName = ref("");
function clearExport() {
  if (exportUrl.value) URL.revokeObjectURL(exportUrl.value);
  exportUrl.value = "";
  exportName.value = "";
}
let seq = 0,
  received = 0,
  timer: ReturnType<typeof setTimeout> | undefined,
  scrollBefore = 0,
  disposed = false;
let observer: ResizeObserver | undefined;
const inputId = `memory-file-${crypto.randomUUID()}`;
const url = computed(() =>
  source.value
    ? `${source.value}${source.value.includes("?") ? "&" : "?"}instance=${instance.value}`
    : "",
);
function send(event: ParentCommand) {
  frame.value?.contentWindow?.postMessage(
    envelope(event, instance.value, ++seq, props.gameId),
    location.origin,
  );
}
function pause(reason: string) {
  send({ type: "pause", payload: { reason } });
}
function viewport() {
  if (loaded.value && frame.value)
    send({
      type: "viewport",
      payload: {
        width: Math.max(200, frame.value.clientWidth),
        height: Math.max(200, frame.value.clientHeight),
      },
    });
}
function resetFrame() {
  if (disposed) return;
  send({ type: "dispose", payload: null });
  instance.value = crypto.randomUUID();
  seq = 0;
  received = 0;
  loaded.value = false;
  startTimeout();
}
function startTimeout() {
  clearTimeout(timer);
  timer = setTimeout(() => {
    if (!loaded.value)
      loadError.value = "游戏加载超时，请重试。记忆已保留，可先导出。";
  }, 12000);
}
async function load() {
  loadError.value = "";
  try {
    await memory.initialize();
    if (disposed) return;
    if (props.source) {
      const u = new URL(props.source, location.href);
      if (u.origin !== location.origin)
        throw new Error("游戏来源必须与当前页面同源。");
      source.value = u.pathname + u.search;
    } else {
      const response = await fetch("/play-assets/catalog.json", {
        cache: "no-cache",
        credentials: "omit",
      });
      if (!response.ok) throw new Error("游戏制品尚未就绪，请稍后重试。");
      const catalog = await response.json(),
        game = catalog.games?.[props.gameId];
      if (
        catalog.protocol !== 1 ||
        !game ||
        !/^[a-f0-9]{40}$/.test(game.version) ||
        game.hostProtocol !== 1 ||
        game.rulesVersion !== 1
      )
        throw new Error("游戏清单与当前页面不兼容。");
      source.value = `/play-assets/${props.gameId}/${game.version}/index.html`;
    }
    resetFrame();
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : "无法加载游戏。";
  }
}
function receive(e: MessageEvent) {
  if (
    e.origin !== location.origin ||
    e.source !== frame.value?.contentWindow ||
    !childEvent(e.data, instance.value, props.gameId) ||
    e.data.seq <= received
  )
    return;
  received = e.data.seq;
  const event = e.data;
  if (event.type === "ready") {
    loaded.value = true;
    clearTimeout(timer);
    send({
      type: "init",
      payload: {
        memory: clonePayload(memory.draft.value),
        theme: props.theme,
        mode: memory.mode.value,
      },
    });
    viewport();
    if (!props.active || panel.value) pause("已暂停，关闭面板后可继续。");
  } else if (event.type === "checkpoint") memory.update(event.payload);
}
async function openMemory() {
  clearExport();
  pause("正在管理游戏记忆。");
  panel.value = true;
  emit("memoryPanel", true);
  feedback.value = "";
  pending.value = undefined;
  try {
    backups.value = await memory.backups();
  } catch (e) {
    feedback.value =
      e instanceof Error ? e.message : "读取备份失败，可导出本页。";
  }
  await nextTick();
  section.value?.querySelector<HTMLButtonElement>(".memory-close")?.focus();
}
function closeMemory() {
  if (busy.value) return;
  panel.value = false;
  emit("memoryPanel", false);
  pending.value = undefined;
  feedback.value = "";
  section.value?.querySelector<HTMLButtonElement>(".memory-open")?.focus();
}
async function download(all = false) {
  try {
    const records = all
      ? await Promise.all(
          Object.values(memories).map(async (m) => {
            await m.initialize();
            return m.current();
          }),
        )
      : [memory.current()];
    const blob = new Blob([exportMemory(records)], {
        type: "application/json",
      }),
      href = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = href;
    a.download = `wao-${all ? "all-games" : props.gameId}-memory-${new Date().toISOString().replace(/[:.]/g, "-")}.json`;
    clearExport();
    exportUrl.value = href;
    exportName.value = a.download;
    a.hidden = true;
    document.body.append(a);
    a.click();
    a.remove();
    feedback.value = all
      ? `已生成全部已开放游戏的记忆：${Object.values(games)
          .map((g) => g.name)
          .join("、")}。若下载未开始，请点击下方下载文件。`
      : `已生成${game.name}记忆。${game.restore}。若下载未开始，请点击下方下载文件。`;
  } catch (e) {
    feedback.value = e instanceof Error ? e.message : "导出失败，请重试。";
  }
}
async function prepare(
  record: MemoryRecord,
  title: string,
  toLocal = memory.mode.value === "local",
) {
  feedback.value = "";
  const target = memories[record.gameId];
  await target.initialize();
  const original = toLocal
    ? await target.previewCurrentDisk()
    : target.current();
  pending.value = {
    record,
    original,
    expected: original?.revision ?? 0,
    toLocal,
    title,
  };
}
async function selectFile(e: Event) {
  const input = e.target as HTMLInputElement,
    file = input.files?.[0];
  input.value = "";
  if (!file || busy.value) return;
  busy.value = true;
  feedback.value = "";
  pending.value = undefined;
  try {
    if (file.size > FILE_LIMIT) throw new Error("文件超过 10 MiB，未导入。");
    const parsed = parseMemory(await file.text());
    const records = parsed.games;
    const toLocal = memory.mode.value === "local";
    const entries = [];
    for (const record of records) {
      const target = memories[record.gameId];
      await target.initialize();
      const original = toLocal
        ? await target.previewCurrentDisk()
        : target.current();
      entries.push({ record, original, expected: original?.revision ?? 0 });
    }
    pending.value = {
      ...entries[0]!,
      additional: entries.slice(1),
      toLocal,
      title:
        records.length > 1
          ? "导入全部游戏记忆"
          : `导入${games[records[0]!.gameId].name}记忆`,
    };
  } catch (e) {
    pending.value = undefined;
    feedback.value =
      e instanceof Error ? e.message : "导入失败，原进度未修改。";
  } finally {
    busy.value = false;
  }
}
async function confirmImport() {
  const item = pending.value;
  if (!item || busy.value) return;
  busy.value = true;
  try {
    if (item.readOnly) await memory.readOther(item.expected);
    else {
      const entries = [item, ...(item.additional ?? [])];
      for (const entry of entries) await memories[entry.record.gameId].flush();
      const records = entries.map((e) => e.record);
      const saved = item.toLocal
        ? await memory.commitBatch(
            records,
            Object.fromEntries(
              entries.map((e) => [e.record.gameId, e.expected]),
            ),
            entries.map((e) => memories[e.record.gameId].current()),
          )
        : records;
      for (const record of saved)
        memories[record.gameId].acceptImported(record, item.toLocal);
    }
    clearExport();
    resetFrame();
    pending.value = undefined;
    backups.value = await memory.backups();
    feedback.value = item.readOnly
      ? "已读取另一页记忆。本页草稿已备份，可以在下方恢复。"
      : item.original
        ? "恢复成功。原记忆已备份，可以关闭面板继续游玩。"
        : "恢复成功，可以关闭面板继续游玩。";
  } catch (e) {
    feedback.value =
      e instanceof Error ? e.message : "恢复失败，原进度未修改。";
    pending.value = undefined;
  } finally {
    busy.value = false;
  }
}
async function previewOverwrite() {
  try {
    await prepare(memory.current(), "把本页记忆保存在本机", true);
  } catch (e) {
    feedback.value =
      e instanceof Error ? e.message : "无法读取本机记忆，可先导出。";
  }
}
async function readOther() {
  try {
    feedback.value = "";
    const record = await memory.previewCurrentDisk();
    if (!record) {
      feedback.value = "本机尚无记忆，本页进度未改动。";
      return;
    }
    pending.value = {
      record,
      original: memory.current(),
      expected: record.revision,
      toLocal: false,
      title: "读取另一页记忆",
      readOnly: true,
    };
  } catch (e) {
    feedback.value = e instanceof Error ? e.message : "读取失败。";
  }
}
async function restore(backup: Backup) {
  try {
    await prepare(backup.original, "恢复备份");
  } catch (e) {
    feedback.value = e instanceof Error ? e.message : "备份读取失败。";
  }
}
async function immerse() {
  if (!immersive.value) {
    scrollBefore = window.scrollY;
    immersive.value = true;
    emit("immersion", true);
  } else {
    immersive.value = false;
    // 父页面先恢复页脚布局，再滚回原处，避免短页面把 scrollTo 钳制为 0。
    emit("immersion", false);
    await nextTick();
    window.scrollTo({ top: scrollBefore, behavior: "instant" });
  }
}
function sessionOnly() {
  pending.value = undefined;
  feedback.value = "";
  void memory.sessionOnly();
}
function trapPanel(e: KeyboardEvent) {
  if (e.key !== "Tab") return;
  const nodes = Array.from(
    (e.currentTarget as HTMLElement).querySelectorAll<HTMLElement>(
      "button:not(:disabled), input:not(:disabled), summary",
    ),
  ).filter((el) => el.getClientRects().length);
  const first = nodes[0],
    last = nodes[nodes.length - 1];
  if (e.shiftKey && document.activeElement === first) {
    e.preventDefault();
    last?.focus();
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault();
    first?.focus();
  }
}
function visibility() {
  if (document.hidden) pause("已暂停：页面进入后台。");
}
function key(e: KeyboardEvent) {
  if (e.key !== "Escape") return;
  if (panel.value) closeMemory();
  else if (immersive.value) void immerse();
  else pause("已暂停。");
}
watch(
  () => props.active,
  (active) => {
    if (!active) pause("已暂停：已切换到呼吸或其他功能。");
  },
);
watch(
  () => props.theme,
  (theme) => send({ type: "theme", payload: { theme } }),
);
watch(memory.status, (status) => {
  if (status === "conflict") pause("另一页更新了记忆，请先处理存档冲突。");
});
onMounted(() => {
  window.addEventListener("message", receive);
  document.addEventListener("visibilitychange", visibility);
  observer = new ResizeObserver(viewport);
  observer.observe(section.value!);
  void load();
});
onBeforeUnmount(() => {
  disposed = true;
  if (exportUrl.value) URL.revokeObjectURL(exportUrl.value);
  send({ type: "dispose", payload: null });
  observer?.disconnect();
  clearTimeout(timer);
  window.removeEventListener("message", receive);
  document.removeEventListener("visibilitychange", visibility);
  emit("immersion", false);
  emit("memoryPanel", false);
  if (immersive.value)
    window.scrollTo({ top: scrollBefore, behavior: "instant" });
});
</script>

<template>
  <section
    ref="section"
    class="game-host"
    :class="{ immersive }"
    :aria-label="`${game.name}游戏容器`"
    @keydown="key"
  >
    <header class="host-bar" :inert="panel">
      <span
        >{{ game.letter }} <small>{{ game.tagline }}</small></span
      >
      <div>
        <button class="memory-open" @click="openMemory">游戏记忆</button
        ><button @click="immerse">
          {{ immersive ? "退出沉浸" : "沉浸游玩" }}</button
        ><button @click="emit('leave')">回到呼吸</button>
      </div>
    </header>
    <div v-if="loadError" class="host-error" role="alert">
      <p>{{ loadError }}</p>
      <button @click="load">重试加载</button
      ><button @click="download()">导出记忆</button>
    </div>
    <div v-else class="frame-wrap" :inert="panel">
      <p v-if="!loaded" class="loading">正在打开{{ game.name }}…</p>
      <iframe
        v-if="source"
        :key="instance"
        ref="frame"
        :src="url"
        :title="game.name"
        allow="autoplay 'self'"
        referrerpolicy="same-origin"
      />
    </div>
    <footer :inert="panel" class="host-status" role="status">
      <span>{{ memory.text.value }}</span
      ><button
        v-if="
          memory.status.value === 'conflict' || memory.status.value === 'error'
        "
        @click="openMemory"
      >
        处理记忆</button
      ><small>{{ game.restore }}</small>
    </footer>
    <section
      v-if="panel"
      class="memory-panel"
      role="dialog"
      aria-modal="true"
      aria-label="游戏记忆"
      @keydown="trapPanel"
    >
      <header>
        <div>
          <small>只留在你手里</small>
          <h2>游戏记忆</h2>
        </div>
        <button class="memory-close" :disabled="busy" @click="closeMemory">
          关闭
        </button>
      </header>
      <p>无需登录，换账号也不会切换进度。{{ summary(memory.current()) }}</p>
      <div class="storage-modes">
        <button
          :aria-pressed="memory.mode.value === 'local'"
          :disabled="busy"
          @click="memory.mode.value === 'session' && previewOverwrite()"
        >
          保存在本机 <small>关键纪录自动保存</small></button
        ><button
          :aria-pressed="memory.mode.value === 'session'"
          :disabled="busy"
          @click="sessionOnly"
        >
          仅本次游玩 <small>不改动已有本机档</small>
        </button>
      </div>
      <p class="memory-status" role="status">{{ memory.text.value }}</p>
      <div class="memory-actions">
        <button @click="download()">导出本款记忆</button
        ><button @click="download(true)">导出全部游戏</button
        ><label class="file-button" :for="inputId">导入记忆</label
        ><input
          :id="inputId"
          ref="fileInput"
          type="file"
          accept=".json,application/json"
          @change="selectFile"
        />
      </div>
      <p class="note">
        导出的是可恢复的游戏存档。本款：{{ game.restore }}。全部导出包含
        {{ Object.keys(games).length }} 款已开放游戏。
      </p>
      <p v-if="exportUrl">
        <a :href="exportUrl" :download="exportName"
          >下载记忆文件（{{ exportName }}）</a
        >
      </p>
      <div
        v-if="
          memory.status.value === 'conflict' || memory.status.value === 'error'
        "
        class="memory-actions"
      >
        <button @click="memory.retry">重试保存</button
        ><button @click="readOther">读取另一页记忆</button
        ><button @click="previewOverwrite">预览覆盖本机</button>
      </div>
      <div v-if="pending" class="import-preview">
        <h3>{{ pending.title }}</h3>
        <p>
          {{ games[pending.record.gameId].name }} ·
          {{ new Date(pending.record.savedAt).toLocaleString() }}
        </p>
        <strong>{{ summary(pending.record) }}</strong>
        <p v-if="pending.original">
          将替换：{{
            summary(pending.original)
          }}。原档先备份，可在对应游戏的记忆面板恢复。
        </p>
        <p v-else>本机尚无{{ games[pending.record.gameId].name }}记忆。</p>
        <div v-for="entry in pending.additional" :key="entry.record.gameId">
          <p>
            {{ games[entry.record.gameId].name }} ·
            {{ new Date(entry.record.savedAt).toLocaleString() }}
          </p>
          <strong>{{ summary(entry.record) }}</strong>
          <p v-if="entry.original">
            将替换：{{ summary(entry.original) }}。原档先备份。
          </p>
        </div>
        <p>
          {{
            pending.readOnly
              ? "保留本机版本；先备份本页草稿，再读取另一页进度。"
              : pending.toLocal
                ? "确认后将预览中的全部游戏写入本机；任何一款冲突时全部取消。"
                : "仅恢复到本次游玩，不写入本机。"
          }}
        </p>
        <button :disabled="busy" @click="confirmImport">
          {{
            busy
              ? "保存中…"
              : pending.readOnly
                ? "备份本页并读取"
                : "备份并恢复"
          }}</button
        ><button :disabled="busy" @click="pending = undefined">取消</button>
      </div>
      <p v-if="feedback" role="status">{{ feedback }}</p>
      <details v-if="backups.length">
        <summary>可恢复备份（{{ backups.length }}）</summary>
        <article v-for="backup in backups.slice(0, 10)" :key="backup.id">
          <p>
            {{ new Date(backup.backedUpAt).toLocaleString() }} ·
            {{ summary(backup.original) }}
          </p>
          <button :disabled="busy" @click="restore(backup)">预览恢复</button>
        </article>
      </details>
    </section>
  </section>
</template>

<style scoped>
.game-host,
.game-host * {
  box-sizing: border-box;
}
.memory-panel a {
  display: inline-block;
  overflow-wrap: anywhere;
  max-width: 100%;
  min-height: 44px;
  padding: 10px 0;
}
.game-host {
  position: relative;
  width: 100%;
  max-width: 1400px;
  margin: 0 auto;
  background: #fff6ed;
  border: 1px solid #e8c5b8;
  border-radius: 24px;
  overflow: hidden;
  color: #633f43;
  font-family: inherit;
}
.host-bar {
  padding: 16px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}
.host-bar > span {
  font-size: 13px;
  font-weight: 750;
}
.host-bar small {
  margin-left: 10px;
  font-size: 11px;
  font-weight: 400;
  color: #8f6b64;
}
.host-bar > div {
  display: flex;
  gap: 8px;
}
button,
.file-button {
  border: 1px solid #d6aaa0;
  background: #fff9f0;
  color: #694047;
  border-radius: 11px;
  padding: 9px 12px;
  min-height: 42px;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
button:hover,
.file-button:hover {
  background: #ffe7d5;
}
button:focus-visible,
.file-button:focus-within {
  outline: 3px solid #be464d;
  outline-offset: 2px;
}
button:disabled {
  opacity: 0.5;
  cursor: wait;
}
iframe {
  width: 100%;
  height: 550px;
  border: 0;
  display: block;
}
.frame-wrap {
  position: relative;
}
.loading {
  position: absolute;
  inset: 0;
  background: #fff6ed;
  display: grid;
  place-items: center;
  z-index: 1;
}
.host-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 20px;
  font-size: 11px;
  border-top: 1px solid #ead2c6;
}
.host-status small {
  font-size: 10px;
  color: #94716a;
}
.host-error {
  padding: 60px 20px;
  text-align: center;
}
.immersive {
  position: fixed;
  inset: 0;
  z-index: 1100;
  max-width: none;
  border-radius: 0;
  border: 0;
  height: 100dvh;
  overflow: auto;
  display: flex;
  flex-direction: column;
}
.immersive .frame-wrap {
  flex: 1;
  min-height: 500px;
}
.immersive iframe {
  height: 100%;
  min-height: 550px;
}
.memory-panel {
  position: absolute;
  inset: 0;
  z-index: 4;
  overflow: auto;
  background: #fff8f0;
  padding: 24px 30px;
  line-height: 1.65;
}
.memory-panel header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.memory-panel h2 {
  font-size: 26px;
  margin: 3px 0 10px;
}
.memory-panel header small {
  color: #b86059;
  font-size: 11px;
  letter-spacing: 3px;
}
.memory-panel p {
  font-size: 12px;
}
.storage-modes {
  display: flex;
  gap: 12px;
  margin-top: 18px;
}
.storage-modes button {
  flex: 1;
  text-align: left;
  padding: 13px;
}
.storage-modes small {
  display: block;
  margin-top: 3px;
  font-size: 10px;
}
.storage-modes [aria-pressed="true"] {
  background: #fbe0cf;
  border: 2px solid #d95d58;
}
.memory-status {
  color: #8d514f;
}
.memory-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin: 12px 0;
}
.file-button {
  display: inline-flex;
  align-items: center;
}
.memory-actions input {
  max-width: 220px;
  font-size: 11px;
  align-self: center;
}
.note {
  color: #9a776e;
  font-size: 11px !important;
}
.import-preview {
  border: 1px solid #d7947f;
  padding: 15px;
  background: #ffead8;
  border-radius: 14px;
  margin: 14px 0;
}
.import-preview h3 {
  margin: 0;
  font-size: 17px;
}
.import-preview strong {
  font-size: 13px;
}
.import-preview button {
  margin-right: 10px;
}
.memory-panel details {
  font-size: 12px;
  margin: 20px 0;
}
.memory-panel article {
  display: flex;
  justify-content: space-between;
  border-top: 1px solid #e9cec0;
  padding: 6px 0;
  gap: 15px;
}
@media (max-width: 600px) {
  .game-host {
    border-radius: 15px;
  }
  .host-bar {
    padding: 10px 12px;
    display: block;
  }
  .host-bar > span {
    display: none;
  }
  .host-bar > div {
    justify-content: space-between;
  }
  .host-bar button {
    font-size: 11px;
    padding: 8px 10px;
  }
  .host-status {
    display: block;
    padding: 12px;
  }
  .host-status small {
    display: block;
    margin-top: 5px;
  }
  iframe {
    height: 530px;
  }
  .memory-panel {
    padding: 18px;
  }
  .memory-panel h2 {
    font-size: 23px;
  }
  .memory-actions {
    gap: 7px;
  }
  .storage-modes {
    gap: 8px;
  }
  .memory-panel article {
    display: block;
  }
  .immersive .frame-wrap {
    min-height: 530px;
  }
  .immersive iframe {
    min-height: 530px;
  }
}
</style>
