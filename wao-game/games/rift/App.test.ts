// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { nextTick } from "vue";
import App from "./App.vue";

vi.mock("./render", () => ({ renderRift: vi.fn() }));
vi.mock("./audio", () => ({
  RiftAudio: class {
    muted = true;
    async unlock() {}
    play() {}
    stop() {}
    dispose() {}
  },
}));
beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal(
    "ResizeObserver",
    class {
      observe() {}
      disconnect() {}
    },
  );
  vi.stubGlobal("matchMedia", () => ({ matches: false }));
  vi.stubGlobal("requestAnimationFrame", (fn: FrameRequestCallback) =>
    setTimeout(() => fn(performance.now()), 16),
  );
  vi.stubGlobal("cancelAnimationFrame", (id: number) => clearTimeout(id));
  Object.defineProperty(HTMLElement.prototype, "setPointerCapture", {
    configurable: true,
    value: vi.fn(),
  });
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  document.body.replaceChildren();
});
it("进入房间立即启用控制；引擎帧同步生命、距离与暂停界面", async () => {
  const w = mount(App, { attachTo: document.body });
  const button = (text: string) =>
    w.findAll("button").find((b) => b.text() === text)!;
  await button("进入远征 ↗").trigger("click");
  await vi.advanceTimersByTimeAsync(50);
  expect(button("攻击 J").attributes("disabled")).toBeUndefined();
  expect(button("暂停").exists()).toBe(true);
  const before = w.get(".room-goal").text();
  await w.get(".arena").trigger("keydown", { code: "KeyD" });
  await vi.advanceTimersByTimeAsync(600);
  await w.get(".arena").trigger("keyup", { code: "KeyD" });
  expect(w.get(".room-goal").text()).not.toBe(before);
  await button("暂停").trigger("click");
  const paused = w.get(".room-goal").text();
  await vi.advanceTimersByTimeAsync(10_000);
  expect(w.get(".room-goal").text()).toBe(paused);
  expect(button("攻击 J").attributes("disabled")).toBeDefined();
  await button("继续战斗").trigger("click");
  await vi.advanceTimersByTimeAsync(50);
  expect(button("攻击 J").attributes("disabled")).toBeUndefined();
  await vi.advanceTimersByTimeAsync(20_000);
  expect(w.text()).toContain("远征暂时停在这里。");
  await button("从检查点再来").trigger("click");
  await vi.advanceTimersByTimeAsync(50);
  expect(w.get(".hero-bars").text()).toContain("生命 110/110");
  w.unmount();
  expect(vi.getTimerCount()).toBe(0);
});
it("轻触蓄力持续积蓄、再次轻触发射；暂停和取消都解除锁定", async () => {
  const w = mount(App, { attachTo: document.body });
  const button = (text: string) =>
    w.findAll("button").find((b) => b.text() === text)!;
  await w
    .findAll("button")
    .find((b) => b.text().includes("✧ 星枪"))!
    .trigger("click");
  await button("进入远征 ↗").trigger("click");
  await vi.advanceTimersByTimeAsync(50);
  await button("按住蓄力：松开发射").trigger("click");
  await button("蓄力 J").trigger("pointerdown", { pointerId: 1 });
  await button("发射 J").trigger("pointerup", { pointerId: 1 });
  await vi.advanceTimersByTimeAsync(500);
  expect(w.get(".hero-bars").text()).not.toContain("蓄力 0%");
  await button("发射 J").trigger("pointerdown", { pointerId: 2 });
  await button("蓄力 J").trigger("pointerup", { pointerId: 2 });
  await vi.advanceTimersByTimeAsync(50);
  expect(w.get(".hero-bars").text()).toContain("蓄力 0%");
  await button("蓄力 J").trigger("pointerdown", { pointerId: 3 });
  await button("发射 J").trigger("pointercancel", { pointerId: 3 });
  expect(button("蓄力 J").exists()).toBe(true);
  expect(button("继续战斗").exists()).toBe(true);
  await button("继续战斗").trigger("click");
  await vi.advanceTimersByTimeAsync(50);
  await button("蓄力 J").trigger("pointerdown", { pointerId: 4 });
  await button("暂停").trigger("click");
  expect(button("蓄力 J").attributes("disabled")).toBeDefined();
  await button("继续战斗").trigger("click");
  await vi.advanceTimersByTimeAsync(100);
  expect(w.get(".hero-bars").text()).toContain("蓄力 0%");
  w.unmount();
  expect(vi.getTimerCount()).toBe(0);
});
it("失焦清理输入并暂停；返回营地停止帧循环，重新进入从安全检查点开始", async () => {
  const w = mount(App, { attachTo: document.body });
  const button = (text: string) =>
    w.findAll("button").find((b) => b.text() === text)!;
  await button("进入远征 ↗").trigger("click");
  await vi.advanceTimersByTimeAsync(50);
  await w.get(".arena").trigger("keydown", { code: "KeyD" });
  window.dispatchEvent(new Event("blur"));
  await nextTick();
  expect(w.text()).toContain("已暂停：离开舞台");
  expect(vi.getTimerCount()).toBe(0);
  await button("回营地").trigger("click");
  expect(w.text()).toContain("检查点已保留");
  await w
    .findAll("button")
    .find((b) => b.text().startsWith("继续检查点"))!
    .trigger("click");
  await vi.advanceTimersByTimeAsync(50);
  expect(button("暂停").exists()).toBe(true);
  w.unmount();
  expect(vi.getTimerCount()).toBe(0);
});
