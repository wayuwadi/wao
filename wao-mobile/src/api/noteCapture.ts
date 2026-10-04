import { api, request } from "./client";
import { capturePayload, type CaptureDraft } from "../domain/noteCapture";
import type { Note } from "../domain/models";

/** 固定 ID 的首次创建，重试返回现有正文，不覆盖 AI 或其他设备的新版本。 */
export async function saveCapture(draft: CaptureDraft): Promise<Note> {
  return draft.rev === undefined
    ? api.createNote(draft.id, capturePayload(draft))
    : api.patchNote(draft.id, capturePayload(draft));
}
export async function saveCaptureThoughts(
  id: string,
  rev: number,
  prefix: string,
  thoughts: string,
): Promise<Note> {
  return request<Note>(`/api/docs/${encodeURIComponent(id)}`, "PATCH", {
    expectedRev: rev,
    content: { version: 1, document: null, markdown: prefix + thoughts },
  });
}
