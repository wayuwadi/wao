import type { NoteFullscreenReminderSettings } from '@/stores/preferences'

const key = (memberId: string) => `wao-settings-reminder-draft-v1:${memberId}`

export function readReminderDraft(memberId: string): NoteFullscreenReminderSettings | null {
  try {
    const value = JSON.parse(localStorage.getItem(key(memberId)) ?? 'null')
    return typeof value?.enabled === 'boolean' && [1, 2, 3, 5, 10, 15].includes(value?.delayMinutes)
      ? { enabled: value.enabled, delayMinutes: value.delayMinutes } : null
  } catch { return null }
}

export function rememberReminderDraft(memberId: string, draft: NoteFullscreenReminderSettings | null): boolean {
  try {
    if (draft) localStorage.setItem(key(memberId), JSON.stringify(draft))
    else localStorage.removeItem(key(memberId))
    return true
  } catch { return false }
}
