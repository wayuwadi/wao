import type { Draft } from "../domain/models";
const key = (account: string) => `wao-mobile:drafts:v1:${account}`;
export function readDrafts(account: string): Draft[] {
  const raw = localStorage.getItem(key(account));
  if (!raw) return [];
  const parsed: unknown = JSON.parse(raw);
  if (
    !Array.isArray(parsed) ||
    parsed.some(
      (d) =>
        !d ||
        !["note", "task"].includes(d.kind) ||
        typeof d.id !== "string" ||
        typeof d.title !== "string",
    )
  )
    throw new Error("本机草稿格式异常，原始内容已保留。请勿清除应用数据。");
  return parsed as Draft[];
}
export function writeDraft(account: string, draft: Draft) {
  const all = readDrafts(account);
  localStorage.setItem(
    key(account),
    JSON.stringify([draft, ...all.filter((d) => d.id !== draft.id)]),
  );
}
export function removeDraft(account: string, id: string) {
  localStorage.setItem(
    key(account),
    JSON.stringify(readDrafts(account).filter((d) => d.id !== id)),
  );
}
