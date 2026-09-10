// @vitest-environment jsdom
import { mount, flushPromises } from "@vue/test-utils";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App.vue";

const mocks = vi.hoisted(() => ({
  invoke: vi.fn().mockResolvedValue(undefined),
  show: vi.fn(),
  hide: vi.fn(),
  check: vi.fn(),
  handlers: new Map<string, (event: { payload: string }) => void>(),
}));
vi.mock("@tauri-apps/api/core", () => ({
  isTauri: () => true,
  invoke: mocks.invoke,
}));
vi.mock("@tauri-apps/api/app", () => ({ getVersion: async () => "1.0.0" }));
vi.mock("@tauri-apps/api/event", () => ({
  listen: async (event: string, fn: (event: { payload: string }) => void) => {
    mocks.handlers.set(event, fn);
    return () => mocks.handlers.delete(event);
  },
}));
vi.mock("@tauri-apps/api/window", () => ({
  getCurrentWindow: () => ({
    show: mocks.show,
    hide: mocks.hide,
    minimize: vi.fn(),
    toggleMaximize: vi.fn(),
  }),
}));
vi.mock("@tauri-apps/plugin-updater", () => ({ check: mocks.check }));
vi.mock("@tauri-apps/plugin-process", () => ({ relaunch: vi.fn() }));

describe("desktop workspace preservation", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.handlers.clear();
    vi.spyOn(navigator, "platform", "get").mockReturnValue("Win32");
  });
  it("hides the existing webview for settings and restores the same workspace", async () => {
    const wrapper = mount(App);
    await flushPromises();
    await wrapper.get(".update-button").trigger("click");
    await flushPromises();
    expect(mocks.invoke).toHaveBeenCalledWith("workspace_action", {
      action: "hide",
    });
    await wrapper.get(".settings-top button").trigger("click");
    await flushPromises();
    expect(mocks.invoke).toHaveBeenCalledWith("workspace_action", {
      action: "show",
    });
    expect(
      mocks.invoke.mock.calls.filter(([name]) => name === "attach_workspace"),
    ).toHaveLength(1);
    expect(mocks.invoke).not.toHaveBeenCalledWith("workspace_action", {
      action: "reload",
    });
    wrapper.unmount();
  });
  it("canceling refresh never reloads or destroys unsaved content", async () => {
    const wrapper = mount(App);
    await flushPromises();
    await wrapper.get('[aria-label="刷新工作区"]').trigger("click");
    await flushPromises();
    expect(wrapper.get('[role="alertdialog"]').text()).toContain(
      "未保存的输入可能丢失",
    );
    await wrapper.get(".modal .secondary").trigger("click");
    await flushPromises();
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false);
    expect(mocks.invoke).not.toHaveBeenCalledWith("workspace_action", {
      action: "reload",
    });
    wrapper.unmount();
  });
  it("closing hides the window and never quits", async () => {
    const wrapper = mount(App);
    await flushPromises();
    await wrapper.get('[aria-label="收起到托盘"]').trigger("click");
    expect(mocks.hide).toHaveBeenCalledOnce();
    expect(mocks.invoke).not.toHaveBeenCalledWith("quit_client");
    wrapper.unmount();
  });
  it("macOS uses native controls and menu quit preserves the workspace until confirmed", async () => {
    vi.spyOn(navigator, "platform", "get").mockReturnValue("MacIntel");
    const wrapper = mount(App);
    await flushPromises();
    expect(wrapper.find(".window-controls").exists()).toBe(false);
    mocks.handlers.get("shell-action")!({ payload: "quit" });
    await flushPromises();
    expect(wrapper.get('[role="alertdialog"]').text()).toContain("退出 WAO");
    expect(mocks.invoke).not.toHaveBeenCalledWith("quit_client");
    await wrapper.get(".modal .secondary").trigger("click");
    await flushPromises();
    expect(mocks.invoke).toHaveBeenCalledWith("workspace_action", {
      action: "show",
    });
    wrapper.unmount();
  });
  it("shows native download results in the toolbar without replacing the workspace", async () => {
    const wrapper = mount(App);
    await flushPromises();
    mocks.handlers.get("workspace-download")!({
      payload: "文件已保存到下载文件夹。",
    });
    await flushPromises();
    expect(wrapper.get("header [role=status]").text()).toContain("文件已保存");
    mocks.handlers.get("workspace-download")!({
      payload: "文件下载失败，请重新点击下载。",
    });
    await flushPromises();
    expect(wrapper.get("header [role=status]").text()).toContain("下载失败");
    expect(mocks.invoke).not.toHaveBeenCalledWith("workspace_action", {
      action: "reload",
    });
    wrapper.unmount();
  });
  it("network recovery does not automatically reload the editor", async () => {
    const wrapper = mount(App);
    await flushPromises();
    window.dispatchEvent(new Event("offline"));
    window.dispatchEvent(new Event("online"));
    await flushPromises();
    expect(mocks.invoke).not.toHaveBeenCalledWith("workspace_action", {
      action: "reload",
    });
    wrapper.unmount();
  });
});
