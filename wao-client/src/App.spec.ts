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
    mocks.check.mockResolvedValue(null);
    vi.stubEnv("DEV", false);
    mocks.handlers.clear();
    vi.spyOn(navigator, "platform", "get").mockReturnValue("Win32");
  });
  it("标题栏复用六个业务动作并同步真实未读，工作区未就绪时禁用", async () => {
    const wrapper = mount(App);
    await flushPromises();
    expect(wrapper.findAll('.quick-actions button')).toHaveLength(6);
    expect(wrapper.get('[data-action="chat"]').attributes('disabled')).toBeDefined();
    mocks.handlers.get('toolbar-state')!({ payload: { ready: true, chat: '3', notifications: '99+' } as unknown as string });
    await flushPromises();
    expect(wrapper.get('[data-action="chat"] small').text()).toBe('3');
    expect(wrapper.get('[data-action="notifications"] small').text()).toBe('99+');
    mocks.handlers.get('toolbar-state')!({ payload: { ready: true, chat: '3', notifications: '99+', theme: 'dark' } as unknown as string });
    await flushPromises();
    expect(wrapper.get('[data-action="theme"]').text()).toContain('🌙');
    expect(wrapper.get('.client').attributes('data-theme')).toBe('dark');

    for (const name of ['new-task', 'new-note', 'theme', 'shortcuts', 'chat', 'notifications']) {
      await wrapper.get(`[data-action="${name}"]`).trigger('click');
      expect(mocks.invoke).toHaveBeenCalledWith('workspace_action', { action: name });
    }
    expect(mocks.invoke).not.toHaveBeenCalledWith('workspace_action', { action: 'reload' });
    wrapper.unmount();
  });
  it("正常连接不显示冗余状态，无新版隐藏更新按钮", async () => {
    const wrapper = mount(App);
    await flushPromises();
    mocks.handlers.get('workspace-state')!({ payload: 'ready' });
    await flushPromises();
    expect(wrapper.find('.connection').exists()).toBe(false);
    expect(wrapper.find('.update-button').exists()).toBe(false);
    expect(wrapper.get('.edition').text()).toBe('WAO');
    wrapper.unmount();
  });
  it("发现新版显示更新圆点，点击直接下载且验签完成才提示安装", async () => {
    let downloaded!: () => void;
    const artifact = { version: '1.1.5', close: vi.fn(), download: vi.fn(() => new Promise<void>(resolve => { downloaded = resolve })), install: vi.fn() };
    mocks.check.mockResolvedValue(artifact);
    const wrapper = mount(App);
    await flushPromises();
    expect(mocks.check).toHaveBeenCalledOnce();
    expect(wrapper.find('.update-dot').exists()).toBe(true);
    await wrapper.get('.update-button').trigger('click');
    await flushPromises();
    expect(artifact.download).toHaveBeenCalledOnce();
    expect(wrapper.find('.settings').exists()).toBe(false);
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false);
    downloaded();
    await flushPromises();
    expect(wrapper.get('[role="alertdialog"]').text()).toContain('安装并重新打开');
    expect(artifact.install).not.toHaveBeenCalled();
    wrapper.unmount();
  });
  it("opens the pet without reloading or hiding the task workspace", async () => {
    const wrapper = mount(App);
    await flushPromises();
    mocks.invoke.mockClear();
    await wrapper.get(".pet-button").trigger("click");
    await flushPromises();
    expect(mocks.invoke).toHaveBeenCalledExactlyOnceWith("workspace_action", { action: "pet" });
    wrapper.unmount();
  });
  it("hides the existing webview for settings and restores the same workspace", async () => {
    const wrapper = mount(App);
    await flushPromises();
    mocks.handlers.get("shell-action")!({ payload: "update" });
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
  it("refresh requests automatic save without a confirmation dialog", async () => {
    const wrapper = mount(App);
    await flushPromises();
    await wrapper.get('[aria-label="刷新工作区"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false);
    expect(mocks.invoke).toHaveBeenCalledWith("workspace_action", { action: "reload" });
    expect(wrapper.find("header .logo").exists()).toBe(false);
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
