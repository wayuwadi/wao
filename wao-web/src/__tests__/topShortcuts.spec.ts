import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { fetchPreference, savePreference } from '@/api/favorites'
import { useSessionStore } from '@/stores/session'
import { favoriteShortcut, normalizeTopShortcuts, resourceShortcut, useTopShortcutsStore } from '@/stores/topShortcuts'
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
function login(id: string) { useSessionStore().accept({ member: { id, name: id } } as never) }
const project = resourceShortcut('project', 'p1', '真实项目')
const kb = resourceShortcut('kb', 'kb1', '真实知识库')
describe('顶部个人资源入口', () => {
  beforeEach(() => {
    setActivePinia(createPinia()); vi.resetAllMocks(); login('member-a')
    vi.mocked(fetchPreference).mockResolvedValue('')
    vi.mocked(savePreference).mockResolvedValue(undefined)
  })
  it('持久化、去重、排序、移除及重新加载使用成员偏好', async () => {
    const store = useTopShortcutsStore(); await store.load()
    await store.pin(project); await store.pin(kb); await store.pin(kb, project.key)
    expect(store.items.map(item => item.key)).toEqual([kb.key, project.key])
    expect(savePreference).toHaveBeenLastCalledWith('nav-top-shortcuts-v1', JSON.stringify([kb, project]))
    vi.mocked(fetchPreference).mockResolvedValue(JSON.stringify(store.items)); await store.load(true)
    expect(store.items).toEqual([kb, project])
    await store.remove(project.key); expect(store.items).toEqual([kb])
  })
  it('加载失败禁止覆盖，保存失败保留已存入口和可重试操作', async () => {
    const store = useTopShortcutsStore()
    vi.mocked(fetchPreference).mockRejectedValueOnce(new Error('读取失败'))
    await store.load(); await expect(store.pin(project)).rejects.toThrow('尚未加载')
    expect(savePreference).not.toHaveBeenCalled()
    await store.retry(); await store.pin(project)
    vi.mocked(savePreference).mockRejectedValueOnce(new Error('断线'))
    await expect(store.pin(kb)).rejects.toThrow('断线')
    expect(store.items).toEqual([project]); expect(store.pending).toEqual([project, kb])
    await store.retry(); expect(store.items).toEqual([project, kb]); expect(store.pending).toBeNull()
  })
  it('保存期间拒绝重复修改，账号切换忽略旧回包', async () => {
    const store = useTopShortcutsStore(); await store.load()
    let finish!: () => void
    vi.mocked(savePreference).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    const saving = store.pin(project)
    await expect(store.pin(kb)).rejects.toThrow('正在保存')
    login('member-b'); finish(); await saving
    expect(store.items).toEqual([]); expect(store.state).toBe('idle'); expect(store.pending).toBeNull()
  })
  it('读取期间切换账号不泄漏上一成员入口', async () => {
    const store = useTopShortcutsStore()
    let finish!: (raw: string) => void
    vi.mocked(fetchPreference).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    const loading = store.load(); login('member-b'); finish(JSON.stringify([project])); await loading
    expect(store.items).toEqual([]); expect(store.loaded).toBe(false)
  })
  it('收藏跳回真实项目与文档，缺少父资源不伪造路径', () => {
    expect(favoriteShortcut({ type: 'project', targetId: 'p1', label: '真实项目', parentId: null })).toEqual(project)
    expect(favoriteShortcut({ type: 'doc', targetId: 'd1', label: '文档', parentId: 'kb1' })?.path).toBe('/kb/kb1?doc=d1')
    expect(favoriteShortcut({ type: 'task', targetId: 't1', label: '任务', parentId: null })).toBeUndefined()
  })
  it('拒绝外部地址、脚本、非法图标、重复引用和错误链接路由', () => {
    for (const path of ['javascript:alert(1)', '//example.com', '/\\example.com', 'https://example.com']) expect(() => normalizeTopShortcuts([{ ...project, path }])).toThrow()
    expect(() => normalizeTopShortcuts([project, project])).toThrow()
    expect(() => normalizeTopShortcuts([{ ...project, icon: 'x onclick=alert(1)' }])).toThrow()
    expect(() => normalizeTopShortcuts([{ ...project, quickLinkId: 'jenkins' }])).toThrow()
  })
})
