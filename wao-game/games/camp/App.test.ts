// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { mount, flushPromises, type VueWrapper } from "@vue/test-utils";
import { nextTick } from "vue";
import App from "./App.vue";
vi.mock("./audio", () => ({
  CampAudio: class {
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
async function click(text: string) {
  const b = w.findAll("button").find((b) => b.text().includes(text))!;
  (b.element as HTMLElement).focus();
  await b.trigger("click");
  await nextTick();
}
async function tick(seconds: number) {
  for (let i = 0; i < seconds * 20; i++) {
    now += 50;
    const pending = [...frames.values()];
    frames.clear();
    pending.forEach((f) => f(now));
    await nextTick();
  }
}
async function enter() {
  await click("布置防线");
  await w.get(".pad-picker").findAll("button")[2]!.trigger("click");
  await click("风铃连弩");
  await click("开始本波");
}
beforeEach(() => {
  now = 0;
  vi.stubGlobal("requestAnimationFrame", (f: FrameRequestCallback) => {
    frames.set(++id, f);
    return id;
  });
  vi.stubGlobal("cancelAnimationFrame", (n: number) => frames.delete(n));
  w = mount(App, { attachTo: document.body });
});
afterEach(() => {
  w.unmount();
  expect(frames.size).toBe(0);
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  document.body.replaceChildren();
});
it("内部建造与升级不误暂停，外部焦点冻结敌人位置，明确继续才推进", async () => {
  await enter();
  await tick(2);
  expect(w.find(".pause-banner").exists()).toBe(false);
  await click("疾风机匣");
  expect(w.find(".pause-banner").exists()).toBe(false);
  const input = document.createElement("input");
  document.body.append(input);
  input.focus();
  await flushPromises();
  expect(w.text()).toContain("输入焦点已离开战场");
  const before = w.get("svg.map-board").html();
  await tick(2);
  expect(w.get("svg.map-board").html()).toBe(before);
  await click("继续守卫");
  await tick(2);
  expect(w.get("svg.map-board").html()).not.toBe(before);
});
it("后台停止动画且前台不自启，控件空格不截获，寒潮瞄准与取消保持暂停", async () => {
  await enter();
  vi.spyOn(document, "hidden", "get").mockReturnValue(true);
  document.dispatchEvent(new Event("visibilitychange"));
  await nextTick();
  expect(frames.size).toBe(0);
  expect(w.text()).toContain("页面已进入后台");
  vi.restoreAllMocks();
  document.dispatchEvent(new Event("visibilitychange"));
  await nextTick();
  expect(frames.size).toBe(0);
  const e = new KeyboardEvent("keydown", {
    bubbles: true,
    cancelable: true,
    code: "Space",
  });
  w.get("select").element.dispatchEvent(e);
  expect(e.defaultPrevented).toBe(false);
  await click("继续守卫");
  await w.get("main").trigger("keydown", { code: "Digit1" });
  expect(w.find(".aim-hint").exists()).toBe(true);
  expect(frames.size).toBe(0);
  await w.get("main").trigger("keydown", { code: "Escape" });
  expect(w.find(".aim-hint").exists()).toBe(false);
  expect(frames.size).toBe(0);
});
it("波内出售回营后恢复完整波前资金与防线，避免保留收入却回退敌人", async () => {
  await enter();
  await tick(5);
  await click("出售");
  expect(w.get(".hud").text()).toContain("投入 55");
  await click("保存回营");
  expect(frames.size).toBe(0);
  await click("继续潮汐回湾");
  expect(w.get(".hud").text()).toContain("165");
  expect(w.get(".build-panel").text()).toContain("风铃连弩 · 1级");
  expect(w.get(".hud").text()).toContain("第 1 / 12 波");
});
it("无防御完整失败停帧，可重开；有防线波间结算只推进一次", async () => {
  await click("布置防线");
  await click("开始本波");
  await tick(70);
  expect(w.get(".result").text()).toContain("这一波，守住了");
  await click("布置下一波");
  await click("开始本波");
  await tick(70);
  await click("布置下一波");
  await click("开始本波");
  await tick(70);
  expect(w.get(".result").text()).toContain("灯暂时暗了");
  expect(frames.size).toBe(0);
  await click("重新守卫这张图");
  expect(w.get(".hud").text()).toContain("20 / 20");
  expect(w.get(".hud").text()).toContain("220");
  expect(w.get(".hud").text()).toContain("第 1 / 12 波");
});
