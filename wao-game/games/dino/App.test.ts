// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import App from "./App.vue";

vi.mock("./render", () => ({ renderDino: vi.fn() }));
vi.mock("./audio", () => ({
  DinoAudio: class {
    muted = true;
    unlock() {}
    play() {}
    stop() {}
    dispose() {}
  },
}));
let observerDisconnected = false;
beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      disconnect() {
        observerDisconnected = true;
      }
    },
  );
  vi.stubGlobal("matchMedia", () => ({ matches: false }));
  vi.stubGlobal("requestAnimationFrame", (fn: FrameRequestCallback) =>
    setTimeout(() => fn(performance.now()), 16),
  );
  vi.stubGlobal("cancelAnimationFrame", (id: number) => clearTimeout(id));
  observerDisconnected = false;
});
afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  document.body.replaceChildren();
});
it("开始按钮消失不会误暂停；暂停不补跑，继续后真实碰撞结算且快速重开", async () => {
  const w = mount(App, { attachTo: document.body });
  const start = w.findAll("button").find((b) => b.text() === "出发 ↗")!;
  (start.element as HTMLElement).focus();
  await start.trigger("click");
  await nextTick();
  expect(w.findAll("button").some((b) => b.text() === "暂停")).toBe(true);
  await vi.advanceTimersByTimeAsync(1000);
  await w
    .findAll("button")
    .find((b) => b.text() === "暂停")!
    .trigger("click");
  expect(w.text()).toContain("已暂停");
  await vi.advanceTimersByTimeAsync(30_000);
  expect(w.text()).not.toContain("碰到了");
  await w
    .findAll("button")
    .find((b) => b.text() === "继续跑")!
    .trigger("click");
  await vi.advanceTimersByTimeAsync(2500);
  expect(w.text()).toContain("碰到了 小仙人掌");
  await w
    .findAll("button")
    .find((b) => b.text() === "再跑一次 ↗")!
    .trigger("click");
  expect(w.text()).not.toContain("碰到了");
  expect(w.text()).toContain("练习 0 / 6");
  w.unmount();
  expect(observerDisconnected).toBe(true);
  expect(vi.getTimerCount()).toBe(0);
});
it("窗口失焦与离开后台不自动继续，其他按键不作用于暂停状态", async () => {
  const w = mount(App, { attachTo: document.body });
  await w
    .findAll("button")
    .find((b) => b.text() === "出发 ↗")!
    .trigger("click");
  window.dispatchEvent(new Event("blur"));
  await nextTick();
  expect(w.text()).toContain("已暂停：离开游戏或失去焦点");
  window.dispatchEvent(new Event("focus"));
  await w.get(".arena").trigger("keydown", { code: "Space" });
  await vi.advanceTimersByTimeAsync(5000);
  expect(w.text()).toContain("已暂停");
  w.unmount();
  expect(vi.getTimerCount()).toBe(0);
});
