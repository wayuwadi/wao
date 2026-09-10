import { mount, flushPromises } from "@vue/test-utils";
import { beforeEach, expect, it, vi } from "vitest";
import { Capacitor } from "@capacitor/core";
import { InAppBrowser } from "@capacitor/inappbrowser";
import GameEntry from "../src/components/GameEntry.vue";

vi.mock("@capacitor/core", () => ({
  Capacitor: { isNativePlatform: vi.fn(() => true) },
}));
vi.mock("@capacitor/inappbrowser", () => ({
  InAppBrowser: { openInWebView: vi.fn() },
  DefaultWebViewOptions: { android: {}, iOS: {} },
  iOSViewStyle: { FULL_SCREEN: 2 },
}));
beforeEach(() => vi.clearAllMocks());

it("在 WAO 内打开游戏，保留游戏会话，不附带 WAO 凭据", async () => {
  const wrapper = mount(GameEntry);
  await wrapper.get("button").trigger("click");
  await flushPromises();
  const request = vi.mocked(InAppBrowser.openInWebView).mock.calls[0]![0];
  expect(request.url).toBe("https://game.wayuwa.cn/");
  expect(request.customHeaders).toBeUndefined();
  expect(request.options).toMatchObject({
    clearCache: false,
    clearSessionCache: false,
    showToolbar: true,
    closeButtonText: "返回 WAO",
    android: { isIsolated: true, hardwareBack: true },
    iOS: { viewStyle: 2 },
  });
  expect(Capacitor.isNativePlatform).toHaveBeenCalled();
});

it("打开期间禁止重复窗口，打开失败后可重试", async () => {
  let rejectOpen!: (reason: Error) => void;
  vi.mocked(InAppBrowser.openInWebView).mockImplementationOnce(
    () =>
      new Promise((_, reject) => {
        rejectOpen = reject;
      }),
  );
  const wrapper = mount(GameEntry);
  await wrapper.get("button").trigger("click");
  expect(wrapper.get("button").attributes("disabled")).toBeDefined();
  await wrapper.get("button").trigger("click");
  expect(InAppBrowser.openInWebView).toHaveBeenCalledTimes(1);
  rejectOpen(new Error("offline"));
  await flushPromises();
  expect(wrapper.get('[role="alert"]').text()).toContain("重试");
  await wrapper.get("button").trigger("click");
  await flushPromises();
  expect(InAppBrowser.openInWebView).toHaveBeenCalledTimes(2);
  expect(wrapper.find('[role="alert"]').exists()).toBe(false);
});
