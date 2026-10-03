// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { afterEach, expect, it, vi } from "vitest";
import { IDBFactory } from "fake-indexeddb";
import GameHost from "../../host/GameHost.vue";
import { exportMemory, MemoryStore } from "../../host/memory";
import { createGameMemory, gameMemoryKey } from "../../host/useGameMemory";
import { envelope } from "../../protocol/camp";
import { envelope as dinoEnvelope } from "../../protocol/dino";

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  document.body.replaceChildren();
});

it("营地握手拒绝其他游戏、伪造来源和旧序号；全量预览读取失败时无可确认的半份导入", async () => {
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      disconnect() {}
    },
  );
  vi.stubGlobal("BroadcastChannel", undefined);
  const database = new IDBFactory();
  vi.stubGlobal("indexedDB", database);
  const store = new MemoryStore(database);
  const memory = createGameMemory(store, "camp");
  const dino = createGameMemory(store, "dino");
  await memory.initialize();
  await dino.initialize();
  const original = memory.current();
  const w = mount(GameHost, {
    attachTo: document.body,
    props: {
      active: true,
      gameId: "camp",
      source: "/camp/index.html",
    },
    global: { provide: { [gameMemoryKey as symbol]: memory } },
  });
  await flushPromises();
  await vi.waitFor(() => expect(w.find("iframe").exists()).toBe(true));
  const iframe = w.get("iframe").element as HTMLIFrameElement;
  const instance = new URL(iframe.src).searchParams.get("instance")!;
  const post = vi
    .spyOn(iframe.contentWindow!, "postMessage")
    .mockImplementation(() => {});
  const ready = envelope(
    { type: "ready", payload: { rulesVersion: 1 } },
    instance,
    1,
  );
  for (const data of [
    dinoEnvelope({ type: "ready", payload: { rulesVersion: 1 } }, instance, 1),
    { ...ready, gameId: "unknown" },
  ]) {
    window.dispatchEvent(
      new MessageEvent("message", {
        data,
        origin: location.origin,
        source: iframe.contentWindow,
      }),
    );
  }
  window.dispatchEvent(
    new MessageEvent("message", {
      data: ready,
      origin: "https://untrusted.invalid",
      source: iframe.contentWindow,
    }),
  );
  window.dispatchEvent(
    new MessageEvent("message", {
      data: ready,
      origin: location.origin,
      source: window,
    }),
  );
  expect(post).not.toHaveBeenCalled();
  window.dispatchEvent(
    new MessageEvent("message", {
      data: ready,
      origin: location.origin,
      source: iframe.contentWindow,
    }),
  );
  expect(post.mock.calls[0]?.[0]).toMatchObject({
    type: "init",
    gameId: "camp",
    payload: { memory: original.payload },
  });
  const count = post.mock.calls.length;
  window.dispatchEvent(
    new MessageEvent("message", {
      data: ready,
      origin: location.origin,
      source: iframe.contentWindow,
    }),
  );
  expect(post.mock.calls).toHaveLength(count);

  await w.get(".memory-open").trigger("click");
  await flushPromises();
  const preview = vi
    .spyOn(memory, "previewCurrentDisk")
    .mockRejectedValueOnce(new Error("测试：第二份存档读取失败"));
  const file = new File(["fixture"], "all-games.json", {
    type: "application/json",
  });
  Object.defineProperty(file, "text", {
    value: async () => exportMemory([dino.current(), memory.current()]),
  });
  Object.defineProperty(w.get("input[type=file]").element, "files", {
    value: [file],
  });
  await w.get("input[type=file]").trigger("change");
  await flushPromises();
  await vi.waitFor(() =>
    expect(w.text()).toContain("测试：第二份存档读取失败"),
  );
  expect(w.find(".import-preview").exists()).toBe(false);
  expect(memory.current().payload).toEqual(original.payload);
  preview.mockRestore();
  await w.get("input[type=file]").trigger("change");
  await flushPromises();
  await vi.waitFor(() => expect(w.find(".import-preview").exists()).toBe(true));
  expect(w.get(".import-preview").text()).toContain("恐龙荒野");
  expect(w.get(".import-preview").text()).toContain("最后一座营地");
  await w
    .findAll("button")
    .find((b) => b.text() === "取消")!
    .trigger("click");
  expect(w.find(".import-preview").exists()).toBe(false);
  expect(memory.current().payload).toEqual(original.payload);
  const blobs: Blob[] = [];
  Object.defineProperty(URL, "createObjectURL", {
    configurable: true,
    value: vi.fn((blob: Blob) => {
      blobs.push(blob);
      return `blob:export-${blobs.length}`;
    }),
  });
  Object.defineProperty(URL, "revokeObjectURL", {
    configurable: true,
    value: vi.fn(),
  });
  const clicked: string[] = [];
  vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    expect(this.isConnected).toBe(true);
    clicked.push(this.download);
  });
  const exportOne = () =>
    w
      .findAll("button")
      .find((b) => b.text() === "导出本款记忆")!
      .trigger("click");
  await exportOne();
  await flushPromises();
  expect(blobs[0]?.type).toBe("application/json");
  expect(clicked[0]).toMatch(/^wao-camp-memory-.*\.json$/);
  expect(w.get(".memory-panel a").attributes("href")).toBe("blob:export-1");
  expect(document.querySelector("a[hidden]")).toBeNull();
  await exportOne();
  await flushPromises();
  expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:export-1");
  await w.get(".memory-close").trigger("click");
  await w.get(".memory-open").trigger("click");
  await flushPromises();
  expect(w.find(".memory-panel a").exists()).toBe(false);
  expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:export-2");
  await exportOne();
  await flushPromises();
  w.unmount();
  expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:export-3");
});
