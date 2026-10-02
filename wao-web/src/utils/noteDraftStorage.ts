/** 小记按成员、文档和浏览器页签保留草稿；新页签仍自动恢复最近一份。 */
const WINDOW_KEY = 'wao-note-draft-window-v2'
type Stored = { raw: string; updatedAt: number }

export function createNoteDraftStorage(persistent: Storage, tab: Storage, owner?: string) {
  function windowId() {
    if (owner) return owner
    let id = tab.getItem(WINDOW_KEY)
    if (!id) { id = crypto.randomUUID(); tab.setItem(WINDOW_KEY, id) }
    return id
  }
  const prefix = (key: string) => `${key}:window-v2:`
  const target = (key: string) => prefix(key) + windowId()
  const initialized = (key: string) => `${key}:initialized-v2`
  function decode(value: string | null): Stored | null {
    if (!value) return null
    try {
      const row = JSON.parse(value) as Stored
      return typeof row.raw === 'string' && Number.isFinite(row.updatedAt) ? row : null
    } catch { return null }
  }
  function write(key: string, raw: string) {
    // 持久副本先落库；页签恢复信息失败时仍保留内容，并由调用者显示草稿失败提示。
    persistent.setItem(target(key), JSON.stringify({ raw, updatedAt: Date.now() } satisfies Stored))
    tab.setItem(initialized(key), 'true')
  }
  function read(key: string): string | null {
    const own = decode(persistent.getItem(target(key)))
    if (own) return own.raw
    // 本页签已保存/清空后，不接管另一页签仍未提交的输入。
    if (tab.getItem(initialized(key)) === 'true') return null
    let latest: Stored | null = null
    for (let index = 0; index < persistent.length; index++) {
      const candidate = persistent.key(index)
      if (!candidate?.startsWith(prefix(key))) continue
      const row = decode(persistent.getItem(candidate))
      if (row && (!latest || row.updatedAt > latest.updatedAt)) latest = row
    }
    const legacy = persistent.getItem(key)
    const raw = latest?.raw ?? legacy
    if (raw !== null) {
      // 先复制到自己的持久键；其他窗口此后改写不会影响已恢复的草稿。
      write(key, raw)
      if (raw === legacy && persistent.getItem(key) === legacy) persistent.removeItem(key)
    } else tab.setItem(initialized(key), 'true')
    return raw
  }
  function remove(key: string, expected?: string | null) {
    const own = decode(persistent.getItem(target(key)))
    if (expected !== undefined && own?.raw !== expected) return
    persistent.removeItem(target(key))
    tab.setItem(initialized(key), 'true')
  }
  return { read, write, remove }
}

const current = () => createNoteDraftStorage(localStorage, sessionStorage)
export const readNoteDraft = (key: string) => current().read(key)
export const writeNoteDraft = (key: string, raw: string) => current().write(key, raw)
export const removeNoteDraft = (key: string, expected?: string | null) => current().remove(key, expected)
