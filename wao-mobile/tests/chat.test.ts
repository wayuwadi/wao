import { beforeEach, describe, expect, it, vi } from "vitest";
import { createChatStore } from "../src/composables/chat";
import { assetPath, messageParts, type ChatInput, type ChatMessage } from "../src/api/chat";
const message = (id = "one"): ChatMessage => ({
  id,
  author: { id: "other", name: "同事" },
  content: "消息",
  createdAt: "2026-09-10T12:00:00Z",
  deleted: false,
  read: false,
  readCount: 0,
  reactions: [],
});
const deferred = <T>() => {
  let resolve!: (v: T) => void, reject!: (v: unknown) => void;
  const promise = new Promise<T>((a, b) => {
    resolve = a;
    reject = b;
  });
  return { promise, resolve, reject };
};
const api = () => ({
  messages: vi.fn(async () => [message()]),
  send: vi.fn(async (_input: ChatInput) => message("sent")),
  read: vi.fn(async () => {}),
  react: vi.fn(async () => message()),
});
beforeEach(() => localStorage.clear());
describe("mobile chat durable delivery", () => {
  it("retries the original token and body after restart, preserving new typing", async () => {
    const a = api();
    a.send.mockRejectedValueOnce(new Error("超时"));
    const first = createChatStore(a);
    first.reset("a", "me");
    first.edit("第一条");
    await first.send();
    const original = a.send.mock.calls[0]![0];
    const second = createChatStore(a);
    second.reset("a", "me");
    second.edit("第二条");
    await second.send();
    expect(a.send.mock.calls[1]![0]).toEqual(original);
    expect(second.draft.value.text).toBe("第二条");
    expect(second.draft.value.pending).toBeNull();
  });
  it("ignores late send and sync results across accounts", async () => {
    const a = api(),
      pending = deferred<ChatMessage>(),
      list = deferred<ChatMessage[]>();
    a.send.mockImplementation(() => pending.promise);
    a.messages.mockImplementation(() => list.promise);
    const c = createChatStore(a);
    c.reset("a", "me");
    c.edit("私有草稿");
    const sent = c.send(),
      loaded = c.refresh();
    c.reset("b", "someone");
    c.edit("新账号草稿");
    pending.resolve(message());
    list.resolve([message()]);
    await Promise.all([sent, loaded]);
    expect(c.messages.value).toEqual([]);
    expect(c.draft.value.text).toBe("新账号草稿");
    c.reset("a", "me");
    expect(c.draft.value.pending?.content).toBe("私有草稿");
  });
  it("retains input typed while a send is pending", async () => {
    const a = api(),
      d = deferred<ChatMessage>();
    a.send.mockImplementation(() => d.promise);
    const c = createChatStore(a);
    c.reset("a", "me");
    c.edit("一");
    const sending = c.send();
    c.edit("二");
    d.resolve(message());
    await sending;
    expect(c.draft.value.text).toBe("二");
  });
  it("blocks network writes when draft storage fails", async () => {
    const a = api(),
      c = createChatStore(a, {
        getItem: () => null,
        setItem: () => {
          throw new Error("quota");
        },
      });
    c.reset("a", "me");
    c.edit("不能丢");
    await c.send();
    expect(a.send).not.toHaveBeenCalled();
    expect(c.draft.value.text).toBe("不能丢");
    expect(c.storageError.value).toBeTruthy();
  });
  it("never overwrites a corrupt persisted outbox", () => {
    const storage = { getItem: () => "{corrupt", setItem: vi.fn() },
      c = createChatStore(api(), storage);
    c.reset("a", "me");
    c.edit("新输入");
    c.retryStorage();
    expect(storage.setItem).not.toHaveBeenCalled();
    expect(c.storageError.value).toBeTruthy();
  });
  it("does not mark hidden history read and uses only the displayed snapshot", async () => {
    const a = api(),
      c = createChatStore(a);
    c.reset("a", "me");
    await c.refresh();
    expect(c.unread.value).toBe(1);
    await c.read("one", () => false);
    expect(a.read).not.toHaveBeenCalled();
    c.active.value = false;
    await c.read("one", () => true);
    expect(a.read).not.toHaveBeenCalled();
    c.active.value = true;
    await c.read("one", () => true);
    expect(a.read).toHaveBeenCalledWith("one");
    expect(c.unread.value).toBe(0);
  });
  it("does not let a stale refresh erase a newly sent message", async () => {
    const a = api(),
      d = deferred<ChatMessage[]>();
    a.messages.mockImplementation(() => d.promise);
    const c = createChatStore(a);
    c.reset("a", "me");
    const loading = c.refresh();
    c.edit("hello");
    await c.send();
    d.resolve([]);
    await loading;
    expect(c.messages.value.map((m) => m.id)).toEqual(["sent"]);
  });
  it("counts unicode characters and retains a reply snapshot", async () => {
    const a = api(),
      c = createChatStore(a);
    c.reset("a", "me");
    c.edit("🌿".repeat(2001));
    await c.send();
    expect(a.send).not.toHaveBeenCalled();
    c.edit("🌿".repeat(2000), {
      id: "reply",
      authorName: "朋友",
      preview: "前文",
    });
    await c.send();
    expect(a.send).toHaveBeenCalledWith(
      expect.objectContaining({ replyToId: "reply" }),
    );
  });
});
it("limits authenticated assets and renders malicious markup as text", () => {
  expect(assetPath("/api/files/abc/download")).toBe("/api/files/abc/download");
  for (const url of [
    "https://evil.example/api/files/abc/download",
    "/api/auth/session",
    "/api/files/abc/download?redirect=1",
    "javascript:alert(1)",
    "http://wao.wayuwa.cn/api/files/abc/download",
  ])
    expect(assetPath(url)).toBeNull();
  const parts = messageParts(
    "<script>alert(1)</script> [bad](javascript:evil) ![image](/api/images/abc/raw)",
  );
  expect(parts.filter((p) => p.kind === "image")).toHaveLength(1);
  expect(parts.some((p) => p.url?.startsWith("javascript:"))).toBe(false);
  expect(parts[0]!.text).toContain("<script>");
});
