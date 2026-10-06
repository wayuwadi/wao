import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { fetchPreference, savePreference } from '@/api/favorites'
import { useSessionStore } from '@/stores/session'
import { useTaskPreferencesStore } from '@/stores/taskPreferences'

vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
const fetch = vi.mocked(fetchPreference), save = vi.mocked(savePreference)
function login(accountId = 'a', id = 'm') {
  useSessionStore().accept({ accountId, member: { id, name: id, initial: id, color: '', role: '' }, identities: [], createdAt: '' })
}
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }

describe('成员任务偏好同步', () => {
  beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); vi.resetAllMocks(); fetch.mockResolvedValue(''); save.mockResolvedValue(); login() })
  it('账号已有偏好优先旧缓存，不写回旧设备；新设备读取相同偏好', async () => {
    localStorage.setItem('wao-project-table-columns-v1:a:m', '["title","status"]')
    localStorage.setItem('wao-day-task-recent-project-v1:a:m', 'old')
    fetch.mockImplementation(async key => key === 'project-table-columns-v1' ? '["title","due"]' : 'remote')
    const first = useTaskPreferencesStore(); await flushPromises()
    expect(first.columns).toEqual(['title','due']); expect(first.recentProject).toBe('remote'); expect(save).not.toHaveBeenCalled()
    localStorage.clear(); setActivePinia(createPinia()); login()
    const second = useTaskPreferencesStore(); await flushPromises()
    expect(second.columns).toEqual(first.columns); expect(second.recentProject).toBe(first.recentProject)
  })
  it('成功读到账号为空才迁移，空设备无需写入；读取失败不覆盖账号', async () => {
    localStorage.setItem('wao-day-task-recent-project-v1:a:m', 'legacy')
    const store = useTaskPreferencesStore(); await flushPromises()
    expect(save).toHaveBeenCalledExactlyOnceWith('task-recent-project-v1', 'legacy'); expect(store.recentState).toBe('saved')
    setActivePinia(createPinia()); login(); save.mockClear(); fetch.mockRejectedValue(new Error('offline'))
    const offline = useTaskPreferencesStore(); await flushPromises()
    expect(offline.recentProject).toBe('legacy'); expect(offline.recentState).toBe('error'); expect(save).not.toHaveBeenCalled()
  })
  it('保存失败保留选择，刷新仍保留待同步内容，明确重试后另一设备恢复', async () => {
    const store = useTaskPreferencesStore(); await flushPromises(); save.mockRejectedValueOnce(new Error('offline'))
    await store.setColumns(['title','number']); expect(store.columns).toEqual(['number','title']); expect(store.columnsState).toBe('error')
    setActivePinia(createPinia()); login(); fetch.mockResolvedValue('["title","status"]')
    const refreshed = useTaskPreferencesStore(); await flushPromises()
    expect(refreshed.columns).toEqual(['number','title']); expect(refreshed.columnsError).toContain('尚未同步')
    await refreshed.retryColumns(); expect(refreshed.columnsState).toBe('saved'); expect(save).toHaveBeenLastCalledWith('project-table-columns-v1', '["number","title"]')
  })
  it('迟到读取不会覆盖新的选择，连续写入按顺序保存最新值', async () => {
    const read = deferred<string>(); fetch.mockImplementation(key => key === 'task-recent-project-v1' ? read.promise : Promise.resolve(''))
    const store = useTaskPreferencesStore(); const write = deferred<void>(); save.mockReturnValueOnce(write.promise)
    const one = store.rememberProject('one'); await flushPromises()
    const two = store.rememberProject('two'); read.resolve('stale'); await flushPromises()
    expect(store.recentProject).toBe('two'); write.resolve(); await Promise.all([one,two])
    expect(save.mock.calls).toEqual([['task-recent-project-v1','one'],['task-recent-project-v1','two']]); expect(store.recentState).toBe('saved')
  })
  it('成员切换隔离旧读取、旧保存及未发送的请求', async () => {
    const read = deferred<string>(); fetch.mockReturnValue(read.promise)
    const store = useTaskPreferencesStore(); const write = deferred<void>(); save.mockReturnValueOnce(write.promise)
    const old = store.rememberProject('old'); await flushPromises()
    const queued = store.rememberProject('queued'); fetch.mockResolvedValue(''); login('b','n'); read.resolve('old-remote'); write.resolve()
    await Promise.all([old,queued]); await flushPromises()
    expect(store.recentProject).toBe(''); expect(store.columns).toEqual(['title','priority','status','owner','start','due'])
    expect(save.mock.calls).toEqual([['task-recent-project-v1','old']])
  })
  it('浏览器缓存不可写时仍保存账号，服务器失败则如实提示草稿保护缺项', async () => {
    const store = useTaskPreferencesStore(); await flushPromises()
    const blocked = vi.spyOn(Storage.prototype,'setItem').mockImplementation(() => { throw new Error('full') })
    await store.rememberProject('one'); expect(store.recentState).toBe('saved')
    save.mockRejectedValueOnce(new Error('offline')); await store.rememberProject('two')
    expect(store.recentError).toContain('无法保护本机偏好'); expect(store.recentProject).toBe('two'); blocked.mockRestore()
  })
  it('标题始终保留且非法字段不进入表格；读取重试恢复账号', async () => {
    fetch.mockRejectedValueOnce(new Error('offline')); const store = useTaskPreferencesStore(); await flushPromises()
    expect(store.columnsState).toBe('error'); fetch.mockResolvedValue('["number"]'); await store.retryColumns()
    expect(store.columns).toEqual(['number','title']); await store.setColumns(['status'])
    expect(store.columns).toEqual(['title','status']); expect(store.columnsState).toBe('saved')
  })
})
