export interface Session {
  accountId: string;
  member: { id: string; name: string; initial: string; color: string };
  identities: { provider: string; email: string | null; displayName: string }[];
}
export interface Project {
  id: string;
  name: string;
  status: string;
}
export interface Task {
  id: string;
  title: string;
  project: string;
  status: string;
  pr: string;
  owner: string;
  due: string;
  desc: string;
  startAt?: string | null;
  endAt?: string | null;
}
export interface Block {
  p?: string;
  h?: string;
  call?: string;
  ul?: string[];
  q?: string[];
}
export interface Note {
  id: string;
  title: string;
  author: string;
  created: string;
  updated: string;
  kbId?: string;
  blocks: Block[];
  content?: { version: number; markdown: string; document?: unknown } | null;
  tags?: string[];
  rev?: number;
}
export interface TaskInput {
  title: string;
  project: string;
  pr: string;
  owner: string;
  due: string;
  desc: string;
}
export interface TaskDraft extends TaskInput {
  kind: "task";
  id: string;
  existing: boolean;
  uncertain?: boolean;
}
export interface NoteDraft {
  kind: "note";
  id: string;
  existing: boolean;
  title: string;
  text: string;
  rev?: number;
  readonly?: boolean;
}
export type Draft = TaskDraft | NoteDraft;
export function localDate(date = new Date()): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}
export function monthDays(
  month: Date,
): { date: string; day: number; current: boolean }[] {
  const start = new Date(month.getFullYear(), month.getMonth(), 1);
  start.setDate(start.getDate() - ((start.getDay() + 6) % 7));
  return Array.from({ length: 42 }, (_, i) => {
    const d = new Date(start);
    d.setDate(d.getDate() + i);
    return {
      date: localDate(d),
      day: d.getDate(),
      current: d.getMonth() === month.getMonth(),
    };
  });
}
export function noteText(note: Note): string {
  return (
    note.content?.markdown ??
    note.blocks
      .map(
        (b) =>
          b.p ??
          b.h ??
          b.call ??
          b.ul?.map((x) => `• ${x}`).join("\n") ??
          b.q?.join("\n") ??
          "",
      )
      .join("\n")
  );
}
export function editableNote(note: Note): boolean {
  return (
    !note.content &&
    note.blocks.every((b) =>
      Object.keys(b).every((k) => k === "p" || b[k as keyof Block] == null),
    )
  );
}
export function newId(): string {
  return `m-${crypto.randomUUID()}`;
}
export function notePayload(draft: NoteDraft) {
  return {
    title: draft.title.trim(),
    blocks: draft.text.split("\n").map((p) => ({ p })),
    ...(draft.existing ? { expectedRev: draft.rev } : {}),
  };
}
