import { beforeEach, describe, it, expect, vi } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import DraftEditor from "../src/components/DraftEditor.vue";
import { useWorkspace } from "../src/composables/workspace";
import { ApiError, api } from "../src/api/client";
import { readDrafts, writeDraft } from "../src/composables/drafts";
vi.mock("../src/api/client", async (importOriginal) => {
  const mod = await importOriginal<typeof import("../src/api/client")>();
  return {
    ...mod,
    api: {
      ...mod.api,
      createNote: vi.fn(),
      patchNote: vi.fn(),
      createTask: vi.fn(),
    },
  };
});
const w = useWorkspace();
beforeEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
  w.session.value = {
    accountId: "a",
    member: { id: "me", name: "测试", initial: "测", color: "" },
    identities: [],
  };
  w.projects.value = [{ id: "p1", name: "日常", status: "进行中" }];
});
describe("用户编辑链路", () => {
  it("输入实时落草稿，服务端失败后保留，可成功重试", async () => {
    const initial = {
      kind: "note" as const,
      id: "m1",
      existing: false,
      title: "",
      text: "",
    };
    const wrapper = mount(DraftEditor, { props: { initial } });
    await wrapper.find("input").setValue("一条真实草稿");
    await wrapper.find("textarea").setValue("正文");
    expect(readDrafts("a")[0]?.title).toBe("一条真实草稿");
    vi.mocked(api.createNote).mockRejectedValueOnce(
      new ApiError("网络断开", 0, true),
    );
    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("网络断开");
    expect(readDrafts("a")).toHaveLength(1);
    vi.mocked(api.createNote).mockResolvedValueOnce({
      id: "m1",
      title: "一条真实草稿",
      author: "me",
      created: "",
      updated: "",
      blocks: [{ p: "正文" }],
      rev: 1,
    });
    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(wrapper.emitted("saved")).toHaveLength(1);
    expect(readDrafts("a")).toHaveLength(0);
    wrapper.unmount();
    expect(readDrafts("a")).toHaveLength(0);
  });
  it("409 冲突不能覆盖云端，可保留另存副本", async () => {
    const initial = {
      kind: "note" as const,
      id: "m1",
      existing: true,
      title: "日记",
      text: "我的修改",
      rev: 2,
    };
    writeDraft("a", initial);
    vi.mocked(api.patchNote).mockRejectedValueOnce(
      new ApiError("已在电脑更新", 409),
    );
    const wrapper = mount(DraftEditor, { props: { initial } });
    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("另存副本");
    expect(readDrafts("a")[0]?.id).toBe("m1");
    await wrapper.find("button.secondary").trigger("click");
    expect(readDrafts("a")[0]?.id).not.toBe("m1");
    expect(readDrafts("a")[0]?.existing).toBe(false);
    wrapper.unmount();
  });
  it("新建任务不明结果阻止盲重试，草稿仍保留", async () => {
    vi.mocked(api.createTask).mockRejectedValueOnce(
      new ApiError("中断", 0, true),
    );
    const wrapper = mount(DraftEditor, {
      props: {
        initial: {
          kind: "task",
          existing: false,
          id: "m1",
          title: "买菜",
          project: "p1",
          owner: "me",
          due: "2026-09-10",
          pr: "P2",
          desc: "",
        },
      },
    });
    await wrapper.find("form").trigger("submit");
    await flushPromises();
    expect(wrapper.text()).toContain("上次提交可能已经成功");
    expect(wrapper.find("button.primary").attributes("disabled")).toBeDefined();
    expect(readDrafts("a")).toHaveLength(1);
    wrapper.unmount();
  });
});
