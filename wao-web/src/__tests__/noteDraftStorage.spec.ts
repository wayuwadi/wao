import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createNoteDraftStorage, initializeNoteDraftIdentity } from '@/utils/noteDraftStorage'

class TabStorage implements Storage {
  values = new Map<string, string>()
  get length() { return this.values.size }
  clear() { this.values.clear() }
  getItem(key: string) { return this.values.get(key) ?? null }
  key(index: number) { return [...this.values.keys()][index] ?? null }
  removeItem(key: string) { this.values.delete(key) }
  setItem(key: string, value: string) { this.values.set(key, value) }
}
const key = 'wao-note-edit-v1:member:note'
const draft = (text: string) => JSON.stringify({ id: 'note', text })

describe('小记同源页签草稿', () => {
  beforeEach(() => { localStorage.clear(); vi.restoreAllMocks() })
  it('两页签独立输入、组件重建与整页刷新均保持各自正文', () => {
    const tabA = new TabStorage(), tabB = new TabStorage()
    const a = createNoteDraftStorage(localStorage, tabA, 'A')
    const b = createNoteDraftStorage(localStorage, tabB, 'B')
    expect(a.read(key)).toBeNull(); expect(b.read(key)).toBeNull()
    a.write(key, draft('A 的输入')); b.write(key, draft('B 的输入'))
    expect(createNoteDraftStorage(localStorage, tabA, 'A').read(key)).toBe(draft('A 的输入'))
    expect(createNoteDraftStorage(localStorage, tabB, 'B').read(key)).toBe(draft('B 的输入'))
  })
  it('复制页签继承会话存储后，两边输入仍独立', async () => {
    const held = new Set<string>()
    const locks = { request: async (name: string, _options: unknown, callback: (lock: unknown) => unknown) => {
      if (held.has(name)) return callback(null)
      held.add(name)
      return callback({ name })
    } } as unknown as LockManager
    const original = new TabStorage()
    const originalId = await initializeNoteDraftIdentity(localStorage, original, locks)
    const a = createNoteDraftStorage(localStorage, original)
    a.write(key, draft('复制前'))
    a.write('wao-quick-note-draft-v1:member', draft('新建区草稿'))
    const copied = new TabStorage()
    copied.values = new Map(original.values)
    expect(await initializeNoteDraftIdentity(localStorage, copied, locks)).not.toBe(originalId)
    const b = createNoteDraftStorage(localStorage, copied)
    expect(b.read(key)).toBe(draft('复制前'))
    expect(b.read('wao-quick-note-draft-v1:member')).toBe(draft('新建区草稿'))
    a.write(key, draft('原页签继续写'))
    b.write(key, draft('复制页签继续写'))
    expect(a.read(key)).toBe(draft('原页签继续写'))
    expect(b.read(key)).toBe(draft('复制页签继续写'))
    a.remove(key)
    expect(b.read(key)).toBe(draft('复制页签继续写'))
  })
  it('刷新释放旧锁后保持自己的身份，不接管其他页签', async () => {
    const locks = { request: async (_name: string, _options: unknown, callback: (lock: unknown) => unknown) => callback({}) } as unknown as LockManager
    const tab = new TabStorage()
    const id = await initializeNoteDraftIdentity(localStorage, tab, locks)
    createNoteDraftStorage(localStorage, tab).write(key, draft('本页输入'))
    expect(await initializeNoteDraftIdentity(localStorage, tab, locks)).toBe(id)
    expect(createNoteDraftStorage(localStorage, tab).read(key)).toBe(draft('本页输入'))
  })
  it('不支持页签锁时明确失败，不轮换身份或生成陈旧副本', async () => {
    const tab = new TabStorage()
    tab.setItem('wao-note-draft-window-v2', 'original')
    const storage = createNoteDraftStorage(localStorage, tab)
    storage.write(key, draft('必须保留'))
    await expect(initializeNoteDraftIdentity(localStorage, tab)).rejects.toThrow('Web Locks')
    expect(tab.getItem('wao-note-draft-window-v2')).toBe('original')
    expect(localStorage.length).toBe(1)
    expect(storage.read(key)).toBe(draft('必须保留'))
  })
  it('既有身份刷新后保存，不留下可被新页签复活的迁移副本', async () => {
    const locks = { request: async (_name: string, _options: unknown, callback: (lock: unknown) => unknown) => callback({}) } as unknown as LockManager
    const tab = new TabStorage()
    tab.setItem('wao-note-draft-window-v2', 'existing')
    const storage = createNoteDraftStorage(localStorage, tab)
    storage.write(key, draft('已保存'))
    await initializeNoteDraftIdentity(localStorage, tab, locks)
    storage.remove(key)
    expect(localStorage.length).toBe(0)
    expect(createNoteDraftStorage(localStorage, new TabStorage(), 'new').read(key)).toBeNull()
  })
  it('复制草稿存储失败时保持来源和原身份，不误报初始化成功', async () => {
    const tab = new TabStorage()
    tab.setItem('wao-note-draft-window-v2', 'original')
    const storage = createNoteDraftStorage(localStorage, tab)
    storage.write(key, draft('必须保留'))
    const locks = { request: async (name: string, _options: unknown, callback: (lock: unknown) => unknown) => callback(name.endsWith(':original') ? null : {}) } as unknown as LockManager
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    await expect(initializeNoteDraftIdentity(localStorage, tab, locks)).rejects.toThrow('quota')
    expect(tab.getItem('wao-note-draft-window-v2')).toBe('original')
    expect(storage.read(key)).toBe(draft('必须保留'))
  })
  it('保存或清空本页签，不清除或接管另一页签的待保存输入', () => {
    const a = createNoteDraftStorage(localStorage, new TabStorage(), 'A')
    const b = createNoteDraftStorage(localStorage, new TabStorage(), 'B')
    a.write(key, draft('A')); b.write(key, draft('B'))
    a.remove(key, draft('A'))
    expect(a.read(key)).toBeNull()
    expect(b.read(key)).toBe(draft('B'))
  })
  it('旧请求的成功清理不能移除本页签后来写入的草稿', () => {
    const a = createNoteDraftStorage(localStorage, new TabStorage(), 'A')
    a.write(key, draft('先提交')); a.write(key, draft('后来输入'))
    a.remove(key, draft('先提交'))
    expect(a.read(key)).toBe(draft('后来输入'))
  })
  it('新页签自动恢复最近持久副本，恢复后另一页签修改不改变它', () => {
    const a = createNoteDraftStorage(localStorage, new TabStorage(), 'A')
    const b = createNoteDraftStorage(localStorage, new TabStorage(), 'B')
    vi.spyOn(Date, 'now').mockReturnValueOnce(10).mockReturnValueOnce(20)
    a.write(key, draft('较早')); b.write(key, draft('最近'))
    const restored = createNoteDraftStorage(localStorage, new TabStorage(), 'C')
    expect(restored.read(key)).toBe(draft('最近'))
    b.write(key, draft('B 又修改'))
    expect(restored.read(key)).toBe(draft('最近'))
  })
  it('旧共享键先复制再迁移，旧客户端后续写入不能覆盖本页签', () => {
    localStorage.setItem(key, draft('旧草稿'))
    const a = createNoteDraftStorage(localStorage, new TabStorage(), 'A')
    expect(a.read(key)).toBe(draft('旧草稿'))
    expect(localStorage.getItem(key)).toBeNull()
    localStorage.setItem(key, draft('旧客户端继续写'))
    expect(a.read(key)).toBe(draft('旧草稿'))
    a.remove(key)
    expect(localStorage.getItem(key)).toBe(draft('旧客户端继续写'))
  })
  it('持久写入失败保留旧共享草稿，不产生成功迁移假象', () => {
    localStorage.setItem(key, draft('必须保留'))
    const a = createNoteDraftStorage(localStorage, new TabStorage(), 'A')
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
    expect(() => a.read(key)).toThrow('quota')
    expect(localStorage.getItem(key)).toBe(draft('必须保留'))
  })
  it('成员、新建与不同小记互相隔离，损坏副本不阻止恢复其他有效草稿', () => {
    const a = createNoteDraftStorage(localStorage, new TabStorage(), 'A')
    a.write(key, draft('本人'))
    a.write('wao-quick-note-draft-v1:member', draft('新建'))
    localStorage.setItem(key + ':window-v2:broken', 'malformed')
    const b = createNoteDraftStorage(localStorage, new TabStorage(), 'B')
    expect(b.read('wao-note-edit-v1:other:note')).toBeNull()
    expect(b.read('wao-note-edit-v1:member:another')).toBeNull()
    expect(b.read(key)).toBe(draft('本人'))
    expect(b.read('wao-quick-note-draft-v1:member')).toBe(draft('新建'))
  })
})
