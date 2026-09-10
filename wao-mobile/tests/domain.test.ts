import { describe, it, expect, beforeEach } from "vitest";
import {
  localDate,
  monthDays,
  editableNote,
  notePayload,
  noteText,
  type Note,
} from "../src/domain/models";
import { writeDraft, readDrafts, removeDraft } from "../src/composables/drafts";
describe("本地日期和月历", () => {
  it("月首按周一排布，闰年不漏 29 日", () => {
    const days = monthDays(new Date(2024, 1, 1));
    expect(days).toHaveLength(42);
    expect(days[0]?.date).toBe("2024-01-29");
    expect(days.some((d) => d.date === "2024-02-29" && d.current)).toBe(true);
  });
  it("保留本地日期，跨年相邻月不错误转换到 UTC", () => {
    expect(localDate(new Date(2026, 0, 1, 0, 5))).toBe("2026-01-01");
    expect(monthDays(new Date(2026, 0, 1))[0]?.date).toBe("2025-12-29");
  });
});
describe("小记无损边界", () => {
  const base: Note = {
    id: "n1",
    title: "记录",
    author: "me",
    created: "",
    updated: "",
    blocks: [{ p: "hello" }],
    rev: 7,
  };
  it("仅允许纯段落直接编辑，富文本和结构化块保持只读", () => {
    expect(editableNote(base)).toBe(true);
    expect(editableNote({ ...base, blocks: [{ h: "标题" }] })).toBe(false);
    expect(
      editableNote({ ...base, content: { version: 1, markdown: "**原文**" } }),
    ).toBe(false);
  });
  it("编辑携带原版本，空行也保留", () => {
    expect(
      notePayload({
        kind: "note",
        id: "n1",
        existing: true,
        title: " 标题 ",
        text: "a\n\nb",
        rev: 7,
      }),
    ).toEqual({
      title: "标题",
      blocks: [{ p: "a" }, { p: "" }, { p: "b" }],
      expectedRev: 7,
    });
  });
  it("读取旧块和 Markdown", () => {
    expect(
      noteText({ ...base, blocks: [{ ul: ["a", "b"] }, { q: ["c", "d"] }] }),
    ).toBe("• a\n• b\nc\nd");
    expect(
      noteText({ ...base, content: { version: 1, markdown: "**a**" } }),
    ).toBe("**a**");
  });
});
describe("草稿持久化", () => {
  beforeEach(() => localStorage.clear());
  it("刷新恢复、账号隔离、保存一个草稿不清掉其他草稿", () => {
    const d = {
      kind: "note" as const,
      id: "n",
      existing: false,
      title: "未提交",
      text: "正文",
    };
    writeDraft("a", d);
    writeDraft("a", { ...d, id: "n2" });
    expect(readDrafts("b")).toEqual([]);
    expect(readDrafts("a")).toHaveLength(2);
    removeDraft("a", "n");
    expect(readDrafts("a")[0]?.id).toBe("n2");
  });
  it("损坏的存档不能被静默覆盖", () => {
    localStorage.setItem("wao-mobile:drafts:v1:a", "broken");
    expect(() =>
      writeDraft("a", {
        kind: "note",
        id: "new",
        existing: false,
        title: "",
        text: "",
      }),
    ).toThrow();
    expect(localStorage.getItem("wao-mobile:drafts:v1:a")).toBe("broken");
  });
});
