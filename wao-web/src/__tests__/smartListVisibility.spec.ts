import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { useSessionStore } from '@/stores/session'
import { useSmartListVisibility } from '@/stores/smartListVisibility'
import { fetchSmartListVisibility, saveSmartListVisibility } from '@/api/smartListVisibility'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import ProjectSettingsModal from '@/components/overlays/ProjectSettingsModal.vue'

vi.mock('@/api/smartListVisibility', () => ({ fetchSmartListVisibility: vi.fn(), saveSmartListVisibility: vi.fn() }))
vi.mock('@/api/board', async original => ({ ...await original<typeof import('@/api/board')>(), fetchMembers: vi.fn(async () => []) }))
const fetch = vi.mocked(fetchSmartListVisibility), save = vi.mocked(saveSmartListVisibility)
function login(id = 'one') {
  useSessionStore().accept({ accountId: id, member: { id, name: id, initial: id, role: '', color: '' }, identities: [], createdAt: '' })
}
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia()); vi.clearAllMocks()
  fetch.mockResolvedValue({}); save.mockResolvedValue()
  login()
})

describe('智能清单显示设置', () => {
  it('默认显示，每个清单独立保存；重开和切换账号读取各自选择', async () => {
    const store = useSmartListVisibility(); await flushPromises()
    expect(store.isVisible('a')).toBe(true)
    await store.setVisible('a', false)
    expect(save).toHaveBeenCalledWith('a', false, '')
    expect(store.isVisible('a')).toBe(false)
    expect(store.isVisible('b')).toBe(true)
    fetch.mockResolvedValueOnce({ a: '0' })
    await store.load()
    expect(store.isVisible('a')).toBe(false)
    fetch.mockResolvedValueOnce({})
    login('two'); await flushPromises()
    expect(store.isVisible('a')).toBe(true)
  })

  it('保存失败保留选择，重试前重新读取，避免覆盖其他设备', async () => {
    const store = useSmartListVisibility(); await flushPromises()
    save.mockRejectedValueOnce(new Error('断网'))
    await store.setVisible('a', false)
    expect(store.isVisible('a')).toBe(false)
    expect(store.pending.a?.desired).toBe('0')
    expect(store.error).toContain('尚未同步')
    fetch.mockRejectedValueOnce(new Error('仍离线'))
    await store.retry('a')
    expect(save).toHaveBeenCalledTimes(1)
    expect(store.pending.a).toBeDefined()
    await store.retry('a')
    expect(save).toHaveBeenCalledTimes(2)
    expect(store.pending.a).toBeUndefined()
  })

  it('跨设备冲突须明确选择后才重写；响应丢失但服务端已保存时安全确认', async () => {
    const store = useSmartListVisibility(); await flushPromises()
    save.mockRejectedValueOnce(Object.assign(new Error('冲突'), { status: 409 }))
    fetch.mockResolvedValueOnce({ a: '1' })
    await store.setVisible('a', false)
    expect(store.conflict('a')).toBe(true)
    expect(store.isVisible('a')).toBe(false)
    expect(save).toHaveBeenCalledTimes(1)
    await store.resolve('a', true)
    expect(save).toHaveBeenLastCalledWith('a', false, '1')
    expect(store.pending.a).toBeUndefined()
    save.mockRejectedValueOnce(new Error('响应丢失'))
    fetch.mockResolvedValueOnce({ a: '1' })
    await store.setVisible('a', true)
    expect(store.pending.a).toBeUndefined()
    expect(store.isVisible('a')).toBe(true)
  })

  it('切换账号后忽略前一个账号迟到的读取结果', async () => {
    let finishOld!: (value: Record<string, string>) => void
    fetch.mockReturnValueOnce(new Promise(resolve => { finishOld = resolve })).mockResolvedValueOnce({})
    const store = useSmartListVisibility()
    login('two'); await flushPromises()
    finishOld({ a: '0' }); await flushPromises()
    expect(store.isVisible('a')).toBe(true)
    expect(store.ready).toBe(true)
  })

  it('未同步的显示选择可在关闭标签页后恢复', async () => {
    const store = useSmartListVisibility(); await flushPromises()
    save.mockRejectedValueOnce(new Error('离线'))
    await store.setVisible('a', false)
    sessionStorage.clear(); setActivePinia(createPinia()); login()
    const reopened = useSmartListVisibility(); await flushPromises()
    expect(reopened.isVisible('a')).toBe(false)
    expect(reopened.pending.a?.desired).toBe('0')
    expect(reopened.error).toContain('尚未同步')
    await reopened.retry('a')
    expect(reopened.pending.a).toBeUndefined()
  })

  it('保存另一清单后仍提醒尚未同步的选择', async () => {
    const store = useSmartListVisibility(); await flushPromises()
    save.mockRejectedValueOnce(new Error('离线'))
    await store.setVisible('a', false)
    await store.setVisible('b', false)
    expect(store.pending.a?.desired).toBe('0')
    expect(store.pending.b).toBeUndefined()
    expect(store.error).toContain('尚未同步')
  })

  it('不同窗口对同一清单的离线选择分别保留并要求确认', async () => {
    const first = useSmartListVisibility(); await flushPromises()
    save.mockRejectedValueOnce(new Error('离线'))
    await first.setVisible('a', false)
    setActivePinia(createPinia()); login()
    const second = useSmartListVisibility(); await flushPromises()
    await second.setVisible('a', true)
    expect(second.conflict('a')).toBe(true)
    sessionStorage.clear(); setActivePinia(createPinia()); login()
    const reopened = useSmartListVisibility(); await flushPromises()
    expect(reopened.conflict('a')).toBe(true)
    await reopened.resolve('a', true)
    expect(reopened.pending.a).toBeUndefined()
    expect(reopened.conflict('a')).toBe(false)
  })

  it('相同离线选择的窗口改选时不能静默删除另一窗口的草稿', async () => {
    const first = useSmartListVisibility(); await flushPromises()
    save.mockRejectedValueOnce(new Error('离线'))
    await first.setVisible('a', false)
    setActivePinia(createPinia()); login()
    const second = useSmartListVisibility(); await flushPromises()
    save.mockRejectedValueOnce(new Error('离线'))
    await second.setVisible('a', false)
    const secondDraft = second.pending.a!
    const calls = save.mock.calls.length
    await first.setVisible('a', true)
    expect(save).toHaveBeenCalledTimes(calls)
    expect(first.conflict('a')).toBe(true)
    expect(JSON.parse(localStorage.getItem(secondDraft.key)!).revision).toBe(secondDraft.revision)
    await first.resolve('a', true)
    expect(save).toHaveBeenLastCalledWith('a', true, '')
    expect(first.pending.a).toBeUndefined()
  })

  it('无法写入草稿时提示保持页面，失败不假称已保存', async () => {
    const store = useSmartListVisibility(); await flushPromises()
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('不可用') })
    save.mockRejectedValueOnce(new Error('离线'))
    await store.setVisible('a', false)
    expect(store.isVisible('a')).toBe(false)
    expect(store.error).toContain('无法保存本机草稿')
    storage.mockRestore()
  })

  it('其他窗口已明确放弃的持久草稿不会在焦点刷新后复活', async () => {
    const first = useSmartListVisibility(); await flushPromises()
    save.mockRejectedValueOnce(new Error('离线'))
    await first.setVisible('a', false)
    setActivePinia(createPinia()); login()
    const second = useSmartListVisibility(); await flushPromises()
    await second.setVisible('a', true)
    await first.load()
    expect(first.conflict('a')).toBe(true)
    await first.resolve('a', false)
    await second.load()
    expect(second.pending.a).toBeUndefined()
    const calls = save.mock.calls.length
    await second.retry('a')
    expect(save).toHaveBeenCalledTimes(calls)
  })

  it('清单设置中可直接更改显示选项，不修改共享清单信息', async () => {
    const data = seedDataStore(), project = data.projects[0]!
    const update = vi.spyOn(data, 'updateProject')
    const wrapper = mount(ProjectSettingsModal, { props: { project }, global: { stubs: { Teleport: true, RouterLink: true } } })
    await flushPromises()
    await wrapper.get('[aria-label="在智能清单中显示"]').setValue('hide'); await flushPromises()
    expect(save).toHaveBeenCalledWith(project.id, false, '')
    expect(wrapper.text()).toContain('仅影响我的所有、今天、明天和最近 7 天')
    expect(wrapper.text()).toContain('不显示在智能清单')
    expect(update).not.toHaveBeenCalled()
    await wrapper.get('.btn-primary').trigger('click')
    expect(wrapper.emitted('close')).toHaveLength(1)
    wrapper.unmount()
  })
})
