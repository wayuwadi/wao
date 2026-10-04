import { beforeEach, describe, expect, it, vi } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import {
  captureMarkdown,
  capturePayload,
  newCapture,
} from "../src/domain/noteCapture";
import NoteCaptureEditor from "../src/components/NoteCaptureEditor.vue";
import { useWorkspace } from "../src/composables/workspace";
import { saveCapture, saveCaptureThoughts } from "../src/api/noteCapture";
import BookNoteReader from "../src/components/BookNoteReader.vue";
import { ApiError, api } from "../src/api/client";
import { iosCapture } from "../src/native/noteCapture";
vi.mock("../src/api/noteCapture", () => ({
  saveCapture: vi.fn(),
  saveCaptureThoughts: vi.fn(),
}));
vi.mock("../src/api/client", async (original) => {
  const mod = await original<typeof import("../src/api/client")>();
  return { ...mod, api: { ...mod.api, note: vi.fn() } };
});
vi.mock("../src/native/noteCapture", () => ({
  isIosCapture: () => true,
  iosCapture: {
    startRecording: vi.fn(),
    stopRecording: vi.fn(),
    transcribe: vi.fn(),
    removeRecording: vi.fn(),
    addListener: vi.fn(async () => ({ remove: vi.fn() })),
    saveImport: vi.fn(),
  },
}));
const w = useWorkspace();
beforeEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
  w.session.value = {
    accountId: "account",
    member: { id: "member", name: "验收", initial: "验", color: "" },
    identities: [],
  };
});
describe("采集书摘与录音草稿", () => {
  it("明确暂不转写后可先保存文字，未转写录音不会删除", async () => {
    const draft = {
      ...newCapture(),
      thoughts: "先记下文字",
      audioId: "pending-audio",
      audioIds: ["pending-audio"],
    };
    const wrapper = mount(NoteCaptureEditor, { props: { initial: draft } });
    await flushPromises();
    const button = wrapper
      .findAll("button")
      .find((b) => b.text().includes("暂不转写"))!;
    await button.trigger("click");
    vi.mocked(saveCapture).mockResolvedValueOnce({
      id: draft.id,
      title: "",
      author: "member",
      created: "",
      updated: "",
      blocks: [],
      content: { version: 1, markdown: draft.thoughts },
      rev: 1,
    });
    await wrapper.get("footer button").trigger("click");
    await flushPromises();
    expect(wrapper.emitted("saved")).toHaveLength(1);
    expect(iosCapture.removeRecording).not.toHaveBeenCalled();
    wrapper.unmount();
  });
  it("感想冲突先比较云端，明确保留草稿后才携带新版本保存", async () => {
    const prefix = "## 书摘\n\n> 合成书摘\n\n## 我的感想\n\n";
    const note = {
      id: "book-note",
      title: "合成书摘",
      author: "member",
      created: "",
      updated: "",
      blocks: [],
      content: { version: 1, markdown: prefix + "原感想" },
      rev: 1,
    };
    const wrapper = mount(BookNoteReader, { props: { note } });
    await wrapper.get("footer button").trigger("click");
    await wrapper.get("textarea").setValue("我的新输入");
    vi.mocked(saveCaptureThoughts).mockRejectedValueOnce(
      new ApiError("已在其他设备更新", 409),
    );
    vi.mocked(api.note).mockResolvedValueOnce({
      ...note,
      rev: 2,
      content: { version: 1, markdown: prefix + "另一设备的感想" },
    });
    await wrapper.get("footer button").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("另一设备的感想");
    expect((wrapper.get("textarea").element as HTMLTextAreaElement).value).toBe(
      "我的新输入",
    );
    await wrapper.get("details button").trigger("click");
    vi.mocked(saveCaptureThoughts).mockResolvedValueOnce({
      ...note,
      rev: 3,
      content: { version: 1, markdown: prefix + "我的新输入" },
    });
    await wrapper.get("footer button").trigger("click");
    await flushPromises();
    expect(saveCaptureThoughts).toHaveBeenLastCalledWith(
      "book-note",
      2,
      prefix,
      "我的新输入",
    );
    expect(
      localStorage.getItem("wao-book-thoughts:v1:account:member:book-note"),
    ).toBeNull();
    wrapper.unmount();
  });
  it("保留空行、转义危险语法；链接和空感想可单独保存", () => {
    expect(newCapture().id).toMatch(/^[a-f0-9]{32}$/);
    const draft = {
      ...newCapture(),
      excerpt: "*原文*\r\n\r\n<script> &lt;",
      sourceUrl: "https://example.org/book",
      thoughts: "",
    };
    const result = captureMarkdown(draft);
    expect(result).toContain("> \\*原文\\*\n> \n> &lt;script&gt; &amp;lt;");
    expect(result).toContain("## 我的感想\n\n");
    expect(capturePayload(draft)).toMatchObject({ createOnly: true });
    expect(captureMarkdown({ ...draft, excerpt: "" })).toContain("example");
    expect(
      captureMarkdown({ ...draft, sourceUrl: "javascript:alert(1)" }),
    ).not.toContain("javascript:");
  });
  it("录音结束自动追加文字，失败保留音频，重试成功才允许保存", async () => {
    const draft = { ...newCapture(), thoughts: "已有想法" };
    const wrapper = mount(NoteCaptureEditor, { props: { initial: draft } });
    await flushPromises();
    await wrapper.find(".record-actions button").trigger("click");
    await flushPromises();
    const audioId = vi.mocked(iosCapture.startRecording).mock.calls[0]![0].id;
    vi.mocked(iosCapture.stopRecording).mockResolvedValue({ audioId });
    vi.mocked(iosCapture.transcribe).mockRejectedValueOnce(
      new Error("识别断网"),
    );
    await wrapper.find(".record-actions button").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("识别断网");
    expect(wrapper.find("footer button").attributes("disabled")).toBeDefined();
    expect(iosCapture.removeRecording).not.toHaveBeenCalled();
    vi.mocked(iosCapture.transcribe).mockResolvedValue({
      text: "散步时想到的事情",
    });
    await wrapper.find(".record-actions button").trigger("click");
    await flushPromises();
    expect(
      (wrapper.find("textarea").element as HTMLTextAreaElement).value,
    ).toBe("已有想法\n\n散步时想到的事情");
    wrapper.unmount();
    expect(
      JSON.parse(
        localStorage.getItem(`wao-capture:v1:account:member:${draft.id}`)!,
      ).audioIds,
    ).toContain(audioId);
  });
  it("失败草稿恢复；不明重试不覆盖云端", async () => {
    const draft = { ...newCapture(), thoughts: "输入" };
    let wrapper = mount(NoteCaptureEditor, { props: { initial: draft } });
    await flushPromises();
    vi.mocked(saveCapture).mockRejectedValueOnce(new Error("网络断开"));
    await wrapper.find("footer button").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("网络断开");
    wrapper.unmount();
    wrapper = mount(NoteCaptureEditor, {
      props: { initial: { ...draft, thoughts: "" } },
    });
    await flushPromises();
    expect(
      (wrapper.find("textarea").element as HTMLTextAreaElement).value,
    ).toBe("输入");
    vi.mocked(saveCapture).mockResolvedValueOnce({
      id: draft.id,
      title: "云端",
      author: "member",
      created: "",
      updated: "",
      blocks: [],
      content: { version: 1, markdown: "云端新正文" },
      rev: 4,
    });
    await wrapper.find("footer button").trigger("click");
    await flushPromises();
    expect(wrapper.text()).toContain("将当前输入另存一篇");
    expect(wrapper.emitted("saved")).toBeUndefined();
    expect(vi.mocked(saveCapture).mock.calls.at(-1)![0].rev).toBeUndefined();
    wrapper.unmount();
  });
  it("保存响应迟到时不进入新成员的小记列表，也不删除原成员草稿", async () => {
    const draft = { ...newCapture(), thoughts: "原成员记录" };
    const wrapper = mount(NoteCaptureEditor, { props: { initial: draft } });
    await flushPromises();
    let resolve!: (value: Awaited<ReturnType<typeof saveCapture>>) => void;
    vi.mocked(saveCapture).mockReturnValueOnce(
      new Promise((r) => {
        resolve = r;
      }),
    );
    await wrapper.find("footer button").trigger("click");
    w.session.value = {
      ...w.session.value!,
      member: { id: "other", name: "其他成员", initial: "其", color: "" },
    };
    w.notes.value = [];
    resolve({
      id: draft.id,
      title: "",
      author: "member",
      created: "",
      updated: "",
      blocks: [],
      content: { version: 1, markdown: draft.thoughts },
      rev: 1,
    });
    await flushPromises();
    expect(w.notes.value).toEqual([]);
    expect(wrapper.emitted("saved")).toBeUndefined();
    expect(
      localStorage.getItem(`wao-capture:v1:account:member:${draft.id}`),
    ).not.toBeNull();
    wrapper.unmount();
  });
  it("损坏草稿不覆盖，恢复失败不能提交", async () => {
    const draft = { ...newCapture(), thoughts: "初始" },
      key = `wao-capture:v1:account:member:${draft.id}`;
    localStorage.setItem(key, "{bad");
    const wrapper = mount(NoteCaptureEditor, { props: { initial: draft } });
    await flushPromises();
    await wrapper.find("textarea").setValue("继续输入");
    wrapper.unmount();
    expect(localStorage.getItem(key)).toBe("{bad");
    expect(saveCapture).not.toHaveBeenCalled();
  });
});
