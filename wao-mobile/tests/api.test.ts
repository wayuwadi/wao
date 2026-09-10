import { beforeEach, describe, it, expect, vi } from "vitest";
import { api, request, ApiError } from "../src/api/client";
import { Capacitor, CapacitorHttp } from "@capacitor/core";
vi.mock("@capacitor/core", () => ({
  Capacitor: { isNativePlatform: vi.fn(() => false) },
  CapacitorHttp: { request: vi.fn() },
}));
beforeEach(() => {
  vi.mocked(Capacitor.isNativePlatform).mockReturnValue(false);
});
describe("真实 API 协议", () => {
  it("使用会话 Cookie，任务仅 PATCH 用户编辑的字段", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue({
        status: 200,
        json: async () => ({ code: 0, data: { id: "t1" } }),
      });
    vi.stubGlobal("fetch", fetch);
    await api.patchTask("t/a", { title: "新标题", due: "2026-09-10" });
    expect(fetch).toHaveBeenCalledWith(
      "/api/tasks/t%2Fa",
      expect.objectContaining({
        method: "PATCH",
        credentials: "include",
        body: JSON.stringify({ title: "新标题", due: "2026-09-10" }),
      }),
    );
  });
  it("登录过期发事件，不能当作成功的空列表", async () => {
    const listener = vi.fn();
    window.addEventListener("wao:unauthorized", listener);
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue({
          status: 401,
          json: async () => ({ code: 401, message: "expired" }),
        }),
    );
    await expect(api.tasks()).rejects.toMatchObject({ status: 401 });
    expect(listener).toHaveBeenCalledOnce();
    window.removeEventListener("wao:unauthorized", listener);
  });
  it("新建任务网络不明不能自动重放", async () => {
    const fetch = vi.fn().mockRejectedValue(new Error());
    vi.stubGlobal("fetch", fetch);
    await expect(
      api.createTask({
        title: "a",
        project: "p",
        owner: "me",
        due: "2026-09-10",
        pr: "P2",
        desc: "",
      }),
    ).rejects.toMatchObject({ uncertain: true });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("原生请求走系统 HTTP，Cookie 不暴露到本机存储", async () => {
    vi.mocked(Capacitor.isNativePlatform).mockReturnValue(true);
    vi.mocked(CapacitorHttp.request).mockResolvedValue({
      status: 200,
      data: { code: 0, data: [] },
      headers: {},
      url: "",
    });
    expect(await api.tasks()).toEqual([]);
    expect(CapacitorHttp.request).toHaveBeenCalledWith(
      expect.objectContaining({
        url: "https://wao.wayuwa.cn/api/tasks",
        disableRedirects: true,
      }),
    );
    expect(localStorage.length).toBe(0);
  });
  it("业务错误与并发冲突保留 HTTP 状态", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue({
          status: 409,
          json: async () => ({ code: 409, message: "文档已更新" }),
        }),
    );
    await expect(request("/api/docs/n", "PATCH", {})).rejects.toEqual(
      new ApiError("文档已更新", 409, false),
    );
  });
});
