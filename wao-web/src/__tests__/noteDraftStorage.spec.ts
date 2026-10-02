import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createNoteDraftStorage } from '@/utils/noteDraftStorage'

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
