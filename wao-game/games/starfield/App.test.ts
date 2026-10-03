// @vitest-environment jsdom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { mount, type VueWrapper } from "@vue/test-utils";
import { nextTick } from "vue";
import App from "./App.vue";

vi.mock("./audio", () => ({
  StarAudio: class {
    muted = true;
    async unlock() {}
    play() {}
    stop() {}
    dispose() {}
  },
}));
let w: VueWrapper;
const button = (text: string) =>
  w.findAll("button").find((b) => b.text().includes(text))!;
async function click(text: string) {
  const b = button(text);
  (b.element as HTMLElement).focus();
  await b.trigger("click");
  await nextTick();
}
async function enter() {
  await click("接取任务");
  await click("帮阿穗加固桥面");
  await vi.advanceTimersByTimeAsync(1500);
}
beforeEach(() => {
  vi.useFakeTimers({
    toFake: ["setTimeout", "clearTimeout", "setInterval", "clearInterval"],
  });
  w = mount(App, { attachTo: document.body });
});
afterEach(async () => {
  vi.unstubAllGlobals();
  w.unmount();
  await vi.advanceTimersByTimeAsync(0);
  expect(vi.getTimerCount()).toBe(0);
  vi.useRealTimers();
  document.body.replaceChildren();
});
it("后台暂停冻结行动，回到前台不会自动继续", async () => {
  await click("接取任务");
  await click("帮阿穗加固桥面");
  vi.spyOn(document, "hidden", "get").mockReturnValue(true);
  document.dispatchEvent(new Event("visibilitychange"));
  await nextTick();
  const before = w.get(".battlefield").text();
  await vi.advanceTimersByTimeAsync(5000);
  expect(w.get(".battlefield").text()).toBe(before);
  expect(w.text()).toContain("页面进入后台");
  vi.restoreAllMocks();
  document.dispatchEvent(new Event("visibilitychange"));
  await nextTick();
  expect(w.find(".overlay").exists()).toBe(true);
  await click("继续这一轮");
  await vi.advanceTimersByTimeAsync(1500);
  expect(w.get(".active-actor").text()).toContain("叶铃");
});
it("技能切换和取消不误暂停、不消耗资源；选目标确认才提交行动", async () => {
  await enter();
  expect(w.get(".active-actor").text()).toContain("叶铃");
  const before = w.get(".battlefield").text();
  await click("缠枝");
  expect(w.find(".overlay").exists()).toBe(false);
  expect(w.get(".selection").text()).toContain("目标：疾行斥候");
  await click("取消选择");
  expect(w.get(".battlefield").text()).toBe(before);
  expect(w.find(".overlay").exists()).toBe(false);
  await click("缠枝");
  await w.get(".arena").trigger("keydown", { code: "ArrowRight" });
  expect(w.get(".selection").text()).toContain("目标：灼羽蜂");
  await click("确认行动");
  expect(w.find(".overlay").exists()).toBe(false);
  expect(w.get(".active-actor").text()).toContain("炽芽");
  expect(w.get(".battlefield").text()).toContain("迟缓");
  expect(w.get(".battlefield").text()).toContain("20/40");
});
it("真实外部焦点暂停敌人计时器，继续后只执行原行动，离开无残留", async () => {
  await click("接取任务");
  await click("帮阿穗加固桥面");
  const outside = document.createElement("input");
  document.body.append(outside);
  outside.focus();
  await nextTick();
  const before = w.get(".battlefield").text();
  expect(w.text()).toContain("已暂停：输入焦点离开战场");
  await vi.advanceTimersByTimeAsync(5000);
  expect(w.get(".battlefield").text()).toBe(before);
  await click("继续这一轮");
  await vi.advanceTimersByTimeAsync(1500);
  expect(w.get(".active-actor").text()).toContain("叶铃");
  await click("暂停");
  await click("回营地调整");
  await click("结束本战并回营地");
  expect(w.find(".arena").exists()).toBe(false);
  expect(w.text()).toContain("0/9");
});
