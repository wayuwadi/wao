import { computed, inject, ref, type InjectionKey } from "vue";
import { freshMemory, isDinoMemory, type DinoMemory } from "../games/dino/save";
import {
  exportMemory,
  MemoryError,
  MemoryStore,
  type Backup,
  type MemoryRecord,
} from "./memory";

export function createGameMemory(memoryStore?: MemoryStore) {
  // 页面会话独立于 WAO 账号，切游戏不丢仅本次草稿；刷新后重新读取本机档。
  const draft = ref(freshMemory()),
    revision = ref(0),
    savedAt = ref(new Date().toISOString());
  const mode = ref<"local" | "session">("local"),
    status = ref<
      "loading" | "saving" | "saved" | "session" | "error" | "conflict"
    >("loading");
  const message = ref(""),
    dirty = ref(false),
    inMemoryBackups = ref<Backup[]>([]);
  let store: MemoryStore | undefined = memoryStore,
    initialized = false,
    initializing: Promise<void> | undefined;
  let writing: Promise<void> | undefined,
    generation = 0,
    channel: BroadcastChannel | undefined;
  function database() {
    return (store ??= new MemoryStore());
  }
  function current(): MemoryRecord {
    return {
      gameId: "dino",
      rulesVersion: 1,
      savedAt: savedAt.value,
      revision: revision.value,
      payload: { ...draft.value },
    };
  }
  function accept(record: MemoryRecord | undefined) {
    draft.value = record ? { ...record.payload } : freshMemory();
    revision.value = record?.revision ?? 0;
    savedAt.value = record?.savedAt ?? new Date().toISOString();
    dirty.value = false;
    generation++;
  }
  function report(e: unknown) {
    status.value =
      e instanceof MemoryError && e.kind === "conflict" ? "conflict" : "error";
    message.value =
      e instanceof Error
        ? e.message
        : "保存失败，进度保留在本页。请重试或导出。";
  }
  async function initialize() {
    if (initialized) return;
    initializing ??= (async () => {
      try {
        accept((await database().all()).find((r) => r.gameId === "dino"));
        status.value = "saved";
      } catch (e) {
        report(e);
      }
      initialized = true;
      if (typeof BroadcastChannel !== "undefined") {
        channel = new BroadcastChannel("wao-game-memory-v1");
        channel.onmessage = (e) => {
          if (
            mode.value === "local" &&
            e.data?.gameId === "dino" &&
            Number.isSafeInteger(e.data.revision) &&
            e.data.revision > revision.value
          ) {
            status.value = "conflict";
            message.value =
              "另一页更新了游戏记忆。本页仍保留，请选择导出、读取另一页或预览覆盖。";
          }
        };
      }
    })();
    await initializing;
  }
  function notify() {
    channel?.postMessage({ gameId: "dino", revision: revision.value });
  }
  async function save() {
    if (writing) return writing;
    if (
      !initialized ||
      !dirty.value ||
      mode.value !== "local" ||
      status.value === "conflict" ||
      status.value === "error"
    )
      return;
    writing = (async () => {
      while (
        dirty.value &&
        mode.value === "local" &&
        status.value !== "conflict" &&
        status.value !== "error"
      ) {
        const version = generation,
          payload = { ...draft.value };
        status.value = "saving";
        try {
          const saved = await database().save(payload, revision.value);
          revision.value = saved.revision;
          savedAt.value = saved.savedAt;
          dirty.value = generation !== version;
          status.value = "saved";
          message.value = "";
          notify();
        } catch (e) {
          report(e);
          break;
        }
      }
    })().finally(() => {
      writing = undefined;
    });
    return writing;
  }
  function update(payload: DinoMemory) {
    if (!isDinoMemory(payload)) return;
    draft.value = { ...payload };
    dirty.value = true;
    generation++;
    savedAt.value = new Date().toISOString();
    if (mode.value === "session") status.value = "session";
    else void save();
  }
  async function retry() {
    await writing;
    try {
      const stored = (await database().all()).find((r) => r.gameId === "dino");
      if ((stored?.revision ?? 0) !== revision.value)
        throw new MemoryError(
          "conflict",
          "重试发现另一页有较新记忆，请先预览或导出本页。",
        );
      status.value = "saved";
      message.value = "";
      await save();
    } catch (e) {
      report(e);
    }
  }
  async function readOther(expected: number) {
    await writing;
    try {
      accept(await database().readWithBackup(current(), expected));
      status.value = mode.value === "local" ? "saved" : "session";
      message.value = "";
    } catch (e) {
      report(e);
      throw e;
    }
  }
  async function sessionOnly() {
    await writing;
    mode.value = "session";
    status.value = "session";
    message.value = "";
  }
  async function previewCurrentDisk() {
    await writing;
    return (await database().all()).find((r) => r.gameId === "dino");
  }
  async function replace(
    record: MemoryRecord,
    expected: number,
    toLocal = mode.value === "local",
  ) {
    await writing;
    if (toLocal) {
      try {
        const saved = (
          await database().replace([record], { dino: expected })
        )[0]!;
        accept(saved);
        mode.value = "local";
        status.value = "saved";
        message.value = "";
        notify();
      } catch (e) {
        report(e);
        throw e;
      }
    } else {
      inMemoryBackups.value.unshift({
        id: crypto.randomUUID(),
        original: current(),
        backedUpAt: new Date().toISOString(),
      });
      accept(record);
      mode.value = "session";
      status.value = "session";
    }
  }
  async function backups() {
    const disk = await database().backups();
    return [...inMemoryBackups.value, ...disk].sort((a, b) =>
      b.backedUpAt.localeCompare(a.backedUpAt),
    );
  }

  const text = computed(() =>
    status.value === "loading"
      ? "正在读取本机记忆…"
      : status.value === "saving"
        ? "保存中…"
        : status.value === "saved"
          ? "已保存在本机 · 无需登录"
          : status.value === "session"
            ? "仅本次游玩 · 离开前可导出"
            : message.value,
  );
  return {
    draft,
    revision,
    mode,
    status,
    message,
    text,
    initialize,
    update,
    retry,
    readOther,
    sessionOnly,
    previewCurrentDisk,
    replace,
    backups,
    current,
    exportCurrent: () => exportMemory([current()]),
    flush: () => writing ?? Promise.resolve(),
  };
}

export const gameMemoryKey: InjectionKey<ReturnType<typeof createGameMemory>> =
  Symbol("wao-game-memory");
let pageMemory: ReturnType<typeof createGameMemory> | undefined;
export function useGameMemory() {
  return (
    inject(gameMemoryKey, undefined) ?? (pageMemory ??= createGameMemory())
  );
}
