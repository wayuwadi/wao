import { onBeforeUnmount } from 'vue'

export interface NoteTagChange { source: string; target: string | null }
const eventName = 'wao-note-tag-change'
const storageKey = 'wao-note-tag-change-v1'

export function replaceTagNames(tags: string[], change: NoteTagChange) {
  return [...new Set(tags.flatMap(tag => tag === change.source ? change.target === null ? [] : [change.target] : [tag]))]
}

/** 只修改当前成员缓存中的标签及标签基线，保留各页签正文和修订信息。 */
export function replaceCachedNoteTags(member: string, change: NoteTagChange, storage: Storage = localStorage) {
  let saved = true
  const prefixes = [`wao-note-edit-v1:${member}:`, `wao-quick-note-draft-v1:${member}`]
  for (let index = 0; index < storage.length; index++) {
    const key = storage.key(index)
    if (!key || !(key.startsWith(prefixes[0]!) || key === prefixes[1] || key.startsWith(prefixes[1] + ':'))) continue
    const raw = storage.getItem(key)
    if (!raw) continue
    try {
      const row = JSON.parse(raw)
      const wrapped = typeof row.raw === 'string'
      const draft = wrapped ? JSON.parse(row.raw) : row
      let changed = false
      for (const values of [draft, draft.baseNote]) {
        if (!Array.isArray(values?.tags) || !values.tags.includes(change.source)) continue
        values.tags = replaceTagNames(values.tags, change)
        changed = true
      }
      if (changed) storage.setItem(key, JSON.stringify(wrapped ? { ...row, raw: JSON.stringify(draft) } : draft))
    } catch { saved = false /* 保留原始副本，并由调用方提醒检查缓存。 */ }
  }
  return saved
}

export function publishNoteTagChange(change: NoteTagChange) {
  window.dispatchEvent(new CustomEvent(eventName, { detail: change }))
  try { localStorage.setItem(storageKey, JSON.stringify({ ...change, id: crypto.randomUUID() })) } catch { /* 当前页仍同步。 */ }
}

export function useNoteTagChanges(callback: (change: NoteTagChange) => void) {
  function receive(change: NoteTagChange) {
    if (!change || typeof change.source !== 'string' || !(change.target === null || typeof change.target === 'string')) return
    callback(change)
  }
  const custom = (event: Event) => receive((event as CustomEvent<NoteTagChange>).detail)
  const storage = (event: StorageEvent) => {
    if (event.key !== storageKey || !event.newValue) return
    try { receive(JSON.parse(event.newValue)) } catch { /* 非法通知不影响编辑。 */ }
  }
  window.addEventListener(eventName, custom)
  window.addEventListener('storage', storage)
  onBeforeUnmount(() => { window.removeEventListener(eventName, custom); window.removeEventListener('storage', storage) })
}
