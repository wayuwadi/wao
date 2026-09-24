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
  register: vi.fn().mockResolvedValue(undefined),
  unregisterAll: vi.fn().mockResolvedValue(undefined),
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
vi.mock("@tauri-apps/plugin-global-shortcut", () => ({
  register: mocks.register,
  unregisterAll: mocks.unregisterAll,
}));

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
    expect(wrapper.findAll(".quick-actions button")).toHaveLength(6);
    expect(
      wrapper.get('[data-action="chat"]').attributes("disabled"),
    ).toBeDefined();
    mocks.handlers.get("toolbar-state")!({
      payload: {
        ready: true,
        chat: "3",
        notifications: "99+",
        theme: "light",
        context: "首页",
        contextItems: ["今日"],
        menus: ["更新日志"],
      } as unknown as string,
    });
    await flushPromises();
    expect(wrapper.get('[data-action="chat"] small').text()).toBe("3");
    expect(wrapper.get('[data-action="notifications"] small').text()).toBe(
      "99+",
    );
    expect(wrapper.get(".workspace-context").text()).toContain("首页");
    await wrapper.get("select.workspace-context").setValue("0");
    expect(mocks.invoke).toHaveBeenCalledWith("workspace_action", {
      action: "context/0",
    });
    await wrapper.get(".workspace-menu-item").trigger("click");
    expect(mocks.invoke).toHaveBeenCalledWith("workspace_action", {
      action: "menu/0",
    });
    mocks.handlers.get("toolbar-state")!({
      payload: {
        ready: true,
        chat: "3",
        notifications: "99+",
        theme: "dark",
        context: "首页",
        contextItems: [],
        menus: [],
      } as unknown as string,
    });
    await flushPromises();
    expect(wrapper.get('[data-action="theme"]').text()).toContain("🌙");
    expect(wrapper.get(".client").attributes("data-theme")).toBe("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(document.documentElement.style.colorScheme).toBe("dark");
    // 工作区导航的占位状态不能把整个外壳闪回浅色。
    mocks.handlers.get("toolbar-state")!({
      payload: { ready: false, theme: "light" } as unknown as string,
    });
    await flushPromises();
    expect(document.documentElement.dataset.theme).toBe("dark");
    mocks.handlers.get("toolbar-state")!({
      payload: { ready: true, theme: "dark" } as unknown as string,
    });
    await flushPromises();

    for (const name of [
      "new-task",
      "new-note",
      "theme",
      "shortcuts",
      "chat",
      "notifications",
    ]) {
      await wrapper.get(`[data-action="${name}"]`).trigger("click");
      expect(mocks.invoke).toHaveBeenCalledWith("workspace_action", {
        action: name,
      });
    }
    expect(mocks.invoke).not.toHaveBeenCalledWith("workspace_action", {
      action: "reload",
    });
    wrapper.unmount();
  });
  it("registers member-configured system shortcuts and reveals the workspace before acting", async () => {
    const wrapper = mount(App);
    await flushPromises();
    mocks.handlers.get("shortcut-config")!({
      payload: { "new-note": "Alt+Shift+Y" } as unknown as string,
    });
    await flushPromises();
    expect(mocks.unregisterAll).toHaveBeenCalled();
    expect(mocks.register).toHaveBeenCalledWith(
      "Alt+Shift+Y",
      expect.any(Function),
    );
    const handler = mocks.register.mock.calls.at(-1)![1];
    handler({ state: "Pressed" });
    await flushPromises();
    expect(mocks.invoke).toHaveBeenCalledWith("workspace_action", {
      action: "reveal",
    });
    expect(mocks.invoke).toHaveBeenCalledWith("workspace_action", {
      action: "new-note",
    });
    wrapper.unmount();
  });
  it("Mac 无新版也能打开版本与更新并返回工作区", async () => {
    vi.spyOn(navigator, "platform", "get").mockReturnValue("MacIntel");
    const wrapper = mount(App);
    await flushPromises();
    mocks.handlers.get("workspace-state")!({ payload: "ready" });
    await flushPromises();
    expect(wrapper.find(".connection").exists()).toBe(false);
    expect(wrapper.get(".update-button").text()).toContain("版本与更新");
    await wrapper.get(".update-button").trigger("click");
    await flushPromises();
    expect(wrapper.get(".settings").text()).toContain("当前版本 1.0.0");
    expect(wrapper.get(".settings").text()).toContain("检查更新");
    expect(mocks.check).toHaveBeenCalledTimes(2);
    await wrapper.get(".settings-top button").trigger("click");
    await flushPromises();
    expect(wrapper.find(".settings").exists()).toBe(false);
    expect(mocks.invoke).toHaveBeenCalledWith("workspace_action", { action: "show" });
    expect(wrapper.get(".edition").text()).toBe("WAO");
    wrapper.unmount();
  });
  it("检查失败时入口仍可见，打开更新页可重试", async () => {
    mocks.check.mockRejectedValueOnce(new Error("offline"));
    const wrapper = mount(App);
    await flushPromises();
    expect(wrapper.get(".update-button").text()).toContain("更新重试");
    await wrapper.get(".update-button").trigger("click");
    await flushPromises();
    expect(mocks.check).toHaveBeenCalledTimes(2);
    expect(wrapper.get(".settings").text()).toContain("你正在使用最新版本");
    wrapper.unmount();
  });
  it("发现新版显示更新圆点，进入更新页确认下载且验签完成才提示安装", async () => {
    let downloaded!: () => void;
    const artifact = {
      version: "1.1.5",
      close: vi.fn(),
      download: vi.fn(
        () =>
          new Promise<void>((resolve) => {
            downloaded = resolve;
          }),
      ),
      install: vi.fn(),
    };
    mocks.check.mockResolvedValue(artifact);
    const wrapper = mount(App);
    await flushPromises();
    expect(mocks.check).toHaveBeenCalledOnce();
    expect(wrapper.find(".update-dot").exists()).toBe(true);
    await wrapper.get(".update-button").trigger("click");
    await flushPromises();
    expect(artifact.download).not.toHaveBeenCalled();
    expect(wrapper.find(".settings").exists()).toBe(true);
    await wrapper.get(".update-actions .primary").trigger("click");
    await flushPromises();
    expect(artifact.download).toHaveBeenCalledOnce();
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false);
    downloaded();
    await flushPromises();
    expect(wrapper.find('[role="alertdialog"]').exists()).toBe(false);
    await wrapper.get(".update-actions .primary").trigger("click");
    await flushPromises();
    expect(wrapper.get('[role="alertdialog"]').text()).toContain(
      "安装并重新打开",
    );
    expect(artifact.install).not.toHaveBeenCalled();
    wrapper.unmount();
  });
  it("opens the pet without reloading or hiding the task workspace", async () => {
    const wrapper = mount(App);
    await flushPromises();
    mocks.invoke.mockClear();
    await wrapper.get(".pet-button").trigger("click");
    await flushPromises();
    expect(mocks.invoke).toHaveBeenCalledExactlyOnceWith("workspace_action", {
      action: "pet",
    });
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
    expect(mocks.invoke).toHaveBeenCalledWith("workspace_action", {
      action: "reload",
    });
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
    expect(wrapper.get(".client").classes()).toContain("macos");
    expect(wrapper.find('[aria-label="工作区快捷操作"]').exists()).toBe(true);
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
