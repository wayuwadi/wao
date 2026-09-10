import { Capacitor, CapacitorHttp } from "@capacitor/core";
import type { Session, Project, Task, TaskInput, Note } from "../domain/models";
export const API_ORIGIN = "https://wao.wayuwa.cn";
export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public uncertain = false,
  ) {
    super(message);
  }
}
export async function request<T>(
  path: string,
  method = "GET",
  data?: unknown,
): Promise<T> {
  let status: number;
  let body: { code: number; message?: string; data: T };
  try {
    if (Capacitor.isNativePlatform()) {
      // Native cookie store manages HttpOnly WAO_SESSION; no token exposed to JS storage.
      const result = await CapacitorHttp.request({
        url: API_ORIGIN + path,
        method,
        data,
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
        },
        connectTimeout: 15_000,
        readTimeout: 60_000,
        responseType: "json",
        disableRedirects: true,
      });
      status = result.status;
      body = result.data;
    } else {
      const response = await fetch(path, {
        method,
        credentials: "include",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
        },
        ...(data === undefined ? {} : { body: JSON.stringify(data) }),
        signal: AbortSignal.timeout(60_000),
        redirect: "error",
      });
      status = response.status;
      body = await response
        .json()
        .catch(() => ({ code: -1, message: "服务器返回了无法识别的内容" }));
    }
  } catch {
    throw new ApiError(
      "连接中断，内容已保留。请检查网络后重试。",
      0,
      method !== "GET",
    );
  }
  if (status === 401 && path === "/api/auth/session") return null as T;
  if (status === 401 && !path.startsWith("/api/auth/")) {
    window.dispatchEvent(new Event("wao:unauthorized"));
    throw new ApiError("登录已过期，请重新登录。草稿仍保留在本机。", 401);
  }
  if (status < 200 || status >= 300 || !body || body.code !== 0)
    throw new ApiError(
      body?.message || `请求失败（${status}）`,
      status,
      method !== "GET" && status >= 500,
    );
  return body.data;
}
const id = encodeURIComponent;
export const api = {
  session: () => request<Session | null>("/api/auth/session"),
  login: (email: string, password: string) =>
    request<Session>("/api/auth/email/login", "POST", { email, password }),
  logout: () => request<void>("/api/auth/logout", "POST"),
  projects: () => request<Project[]>("/api/projects"),
  tasks: () => request<Task[]>("/api/tasks"),
  createTask: (data: TaskInput) => request<Task>("/api/tasks", "POST", data),
  patchTask: (key: string, data: Partial<TaskInput> & { status?: string }) =>
    request<Task>(`/api/tasks/${id(key)}`, "PATCH", data),
  notes: async () =>
    (await request<Note[]>("/api/docs")).filter((n) => n.kbId === "kb-notes"),
  note: (key: string) => request<Note>(`/api/docs/${id(key)}`),
  createNote: (key: string, data: unknown) =>
    request<Note>(`/api/docs/notes/${id(key)}`, "PUT", data),
  patchNote: (key: string, data: unknown) =>
    request<Note>(`/api/docs/${id(key)}`, "PATCH", data),
};
