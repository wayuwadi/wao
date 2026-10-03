import type { NoteAiSettings } from '@/api/noteAiSettings'
let owner = ''
let pending: { draft: NoteAiSettings; version: string } | null = null
/** 仅在当前成员内存中保护未保存设置；切换成员丢弃私人提示词。 */
export function noteSettingsDraft(member: string) {
  if(owner !== member) { owner = member; pending = null }
  return pending ? structuredClone(pending) : null
}
export function rememberNoteSettingsDraft(member: string, value: typeof pending) {
  if(owner !== member) { owner = member; pending = null }
  pending = value ? structuredClone(value) : null
}
