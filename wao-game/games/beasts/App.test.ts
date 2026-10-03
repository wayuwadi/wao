// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { mount, type VueWrapper } from "@vue/test-utils";
import { nextTick } from "vue";
import App from "./App.vue";
vi.mock("./audio", () => ({
  BeastAudio: class {
    muted = true;
    async unlock() {}
    play() {}
    stop() {}
    dispose() {}
  },
}));
let w: VueWrapper,
  now = 0,
  id = 0;
const frames = new Map<number, FrameRequestCallback>();
const button = (text: string) =>
  w.findAll("button").find((b) => b.text().includes(text))!;
async function click(text: string) {
  const b = button(text);
  (b.element as HTMLElement).focus();
  await b.trigger("click");
  await nextTick();
}
async function tick(seconds: number) {
  for (let i = 0; i < seconds * 10; i++) {
    now += 100;
    const pending = [...frames.values()];
    frames.clear();
    pending.forEach((f) => f(now));
    await nextTick();
  }
}
async function enter() {
  await click("出发探索");
  await click("穿过摇铃草");
  await nextTick();
}
beforeEach(() => {
  vi.stubGlobal("requestAnimationFrame", (f: FrameRequestCallback) => {
    frames.set(++id, f);
    return id;
  });
  vi.stubGlobal("cancelAnimationFrame", (id: number) => frames.delete(id));
  w = mount(App, { attachTo: document.body });
});
afterEach(() => {
  w.unmount();
  expect(frames.size).toBe(0);
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  document.body.replaceChildren();
});
it("进入遭遇与内部指令不误暂停，真实外部焦点冻结帧，明确继续", async () => {
  await enter();
  expect(w.find(".pause-banner").exists()).toBe(false);
  await tick(2);
  await click("留手");
  expect(w.find(".pause-banner").exists()).toBe(false);
  const input = document.createElement("input");
  document.body.append(input);
  input.focus();
  await nextTick();
  expect(w.text()).toContain("输入焦点已离开战场");
  const before = w.get(".battlefield").text();
  await tick(5);
  expect(w.get(".battlefield").text()).toBe(before);
  await click("继续遭遇");
  await tick(2);
  expect(w.get(".battlefield").text()).not.toBe(before);
});
it("后台清除帧，前台不自启；输入框快捷键不被游戏截获", async () => {
  await enter();
  vi.spyOn(document, "hidden", "get").mockReturnValue(true);
  document.dispatchEvent(new Event("visibilitychange"));
  await nextTick();
  expect(frames.size).toBe(0);
  expect(w.text()).toContain("页面进入后台");
  vi.restoreAllMocks();
  document.dispatchEvent(new Event("visibilitychange"));
  await nextTick();
  expect(w.find(".pause-banner").exists()).toBe(true);
  const event = new KeyboardEvent("keydown", {
    bubbles: true,
    cancelable: true,
    code: "Space",
  });
  const select = w.get("select").element;
  select.dispatchEvent(event);
  expect(event.defaultPrevented).toBe(false);
  await click("继续遭遇");
  await w.get("main").trigger("keydown", { code: "Space" });
  expect(w.find(".pause-banner").exists()).toBe(true);
});
it("留手安抚捕获后立即结算与停止，返回后真实培养、蛋与目标可用", async () => {
  await enter();
  await click("留手");
  await click("安抚 · 20");
  await tick(30);
  await click("安抚 · 20");
  await tick(5);
  await click("安抚 · 20");
  await click("捕获");
  expect(w.text()).toContain("愿意跟你回家");
  expect(frames.size).toBe(0);
  await click("继续探索");
  expect(w.get(".expedition-progress").text()).toContain("路段 2");
  await click("探访温暖巢穴");
  await click("兽舍与培养");
  expect(w.text()).toContain("1枚蛋");
  await click("喂养升级");
  expect(w.get(".detail-heading").text()).toContain("Lv.2");
  await click("培养成长");
  expect(w.get(".detail-heading").text()).toContain("成长 1/3");
});
it("退出未完成战斗需要页内确认，回到节点后可重试", async () => {
  await enter();
  await tick(3);
  await click("返回路线");
  expect(w.find("[role=dialog]").exists()).toBe(true);
  await w
    .get("[role=dialog]")
    .findAll("button")
    .find((b) => b.text() === "返回路线")!
    .trigger("click");
  expect(w.find(".battlefield").exists()).toBe(false);
  expect(frames.size).toBe(0);
  await click("进入遭遇");
  expect(w.get(".battle-heading").text()).toContain("0 秒");
});
