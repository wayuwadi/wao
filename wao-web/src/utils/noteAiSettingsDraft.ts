import type { NoteAiSettings } from '@/api/noteAiSettings'
type Pending = { draft: NoteAiSettings; version: string }
const retained = new Map<string, Pending>()
const key = (member: string) => `wao-note-ai-settings-draft-v2:${encodeURIComponent(member)}`
/** 成员隔离的本机草稿保留原始 CAS 基准；读取服务器新版本不能隐式重基。 */
export function noteSettingsDraft(member: string): Pending | null {
  let raw: string | null
  try {
    raw = localStorage.getItem(key(member))
  } catch (cause) {
    if (retained.has(member)) return structuredClone(retained.get(member)!)
    throw cause
  }
  if (!raw) return null
  const value = JSON.parse(raw) as Pending
  if (
    typeof value.version !== 'string' ||
    !value.draft ||
    typeof value.draft.title !== 'boolean' ||
    typeof value.draft.polish !== 'boolean' ||
    typeof value.draft.image !== 'boolean' ||
    ['model', 'reasoningEffort', 'titlePrompt', 'polishPrompt', 'imagePrompt'].some(
      (field) => typeof value.draft[field as keyof NoteAiSettings] !== 'string',
    )
  )
    throw new Error('本机小记 AI 设置草稿格式无法读取')
  retained.set(member, structuredClone(value))
  return value
}
/** 存储失败保留内存副本并向界面报告，避免把本机保留误报为成功。 */
export function rememberNoteSettingsDraft(member: string, value: Pending | null) {
  if (value) {
    retained.set(member, structuredClone(value))
    localStorage.setItem(key(member), JSON.stringify(value))
  } else {
    localStorage.removeItem(key(member))
    retained.delete(member)
  }
}
