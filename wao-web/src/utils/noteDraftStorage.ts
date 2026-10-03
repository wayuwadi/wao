/** 小记按成员、文档和浏览器页签保留草稿；新页签仍自动恢复最近一份。 */
const WINDOW_KEY = 'wao-note-draft-window-v2'
type Stored = { raw: string; updatedAt: number }
let identityError: Error | null = null

/** 页签锁只保护身份；不锁住正文，不阻止其他页签正常编辑。 */
export async function initializeNoteDraftIdentity(persistent: Storage, tab: Storage, locks?: LockManager) {
  if (!locks) throw new Error('当前浏览器无法保护小记页签草稿，请使用支持 Web Locks 的浏览器')
  const previous = tab.getItem(WINDOW_KEY)
  const claim = (id: string) => new Promise<boolean>((resolve, reject) => {
    void locks.request(`wao-note-draft:${id}`, { ifAvailable: true }, lock => {
      resolve(!!lock)
      // 浏览器在文档销毁时释放；刷新可沿用身份，复制页签不能领取同一身份。
      if (lock) return new Promise<void>(() => {})
    }).catch(reject)
  })
  if (previous && await claim(previous)) return previous
  const id = crypto.randomUUID()
  if (!await claim(id)) throw new Error('小记草稿页签身份无法独占')
  const copies: [string, string][] = []
  if (previous) {
    const suffix = `:window-v2:${previous}`
    for (let index = 0; index < persistent.length; index++) {
      const key = persistent.key(index)
      if (!key?.endsWith(suffix) || !/^(wao-note-edit-v1:|wao-quick-note-draft-v1:)/.test(key)) continue
      const raw = persistent.getItem(key)
      if (raw !== null) copies.push([key.slice(0, -previous.length) + id, raw])
    }
  }
  // 完整复制后才切身份；失败保留原始副本，并让调用方明确显示存储异常。
  try {
    for (const [key, raw] of copies) persistent.setItem(key, raw)
    tab.setItem(WINDOW_KEY, id)
  } catch (error) {
    for (const [key] of copies) {
      try { persistent.removeItem(key) } catch { /* 来源未动；存储整体不可用时停止恢复操作。 */ }
    }
    throw error
  }
  return id
}

/** 在应用挂载前准备；失败不阻断其他页面，草稿操作由既有错误提示接住。 */
export async function prepareNoteDraftStorage() {
  try {
    await initializeNoteDraftIdentity(localStorage, sessionStorage, navigator.locks)
    identityError = null
    return true
  } catch {
    identityError = new Error('小记草稿保护暂不可用；请保留输入并检查浏览器存储或更新浏览器')
    return false
  }
}

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

const current = () => {
  if (identityError) throw identityError
  return createNoteDraftStorage(localStorage, sessionStorage)
}
export const readNoteDraft = (key: string) => current().read(key)
export const writeNoteDraft = (key: string, raw: string) => current().write(key, raw)
export const removeNoteDraft = (key: string, expected?: string | null) => current().remove(key, expected)
