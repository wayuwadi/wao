import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
const fetchDay = vi.hoisted(() => vi.fn())
vi.mock('@/api/dayWorkspace', async importOriginal => ({ ...await importOriginal<typeof import('@/api/dayWorkspace')>(), fetchDayWorkspace: fetchDay }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }) }))
import TodoView from '@/views/TodoView.vue'
import TodoDayView from '@/components/todo/TodoDayView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useDayWorkspaceStore } from '@/stores/dayWorkspace'
import { seedDataStore } from './helpers/seedDataStore'

describe('待办日工作区', () => {
  beforeEach(() => {
    localStorage.clear(); sessionStorage.clear(); vi.clearAllMocks(); setActivePinia(createPinia())
    const data = useDataStore(); seedDataStore(data); data.today = '2026-10-03'
    useSessionStore().accept({ accountId: 'a', member: { id: 'wg', name: '验收', initial: '验', color: '#4358D6', role: '成员' }, identities: [], createdAt: '' })
  })
  const fixture = (date: string) => {
    const task = useDataStore().tasks[0]!
    return { date, recordingStartedAt: '2026-10-01T00:00:00+08:00', historyCoverage: 'RECORDED', tasks: [{ task: { ...task, title: '后来改名', status: '待办' }, snapshot: { ...task, title: '当天原名称', status: '已完成', start: date, subs: [{ id: 'one', text: '当天步骤', done: true }] }, source: 'RECORDED', planned: true, finished: true, events: [] }], notes: [], summary: { plannedCount: 1, finishedCount: 1, stepDoneCount: 1, noteCount: 0 } }
  }
  it('首次默认按天，同一入口可返回全部待办', async () => {
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [] })
    const wrapper = mount(TodoView, { global: { stubs: { TaskSchedulePopover: true } } })
    await flushPromises()
    expect(wrapper.find('[aria-label="待办按天视图"]').exists()).toBe(true)
    await wrapper.findAll('.workspace-switch button')[1]!.trigger('click')
    expect(wrapper.find('.mt-toolbar').exists()).toBe(true)
    expect(wrapper.find('[aria-label="待办按天视图"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('历史展示旧快照，不能被当前标题和重新打开覆盖', async () => {
    fetchDay.mockResolvedValue(fixture('2026-10-02'))
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-02' } })
    await flushPromises()
    expect(wrapper.get('.task-title').text()).toBe('当天原名称')
    expect(wrapper.get('.task-status').text()).toBe('已完成')
    expect(wrapper.find('.task-check').exists()).toBe(false)
    await wrapper.get('.task-actions button').trigger('click')
    expect(wrapper.get('.historical-subs').text()).toContain('✓ 当天步骤')
    wrapper.unmount()
  })
  it('读取失败不冒充没有安排，可重试或回到全部待办', async () => {
    fetchDay.mockRejectedValueOnce(new Error('断网')).mockResolvedValueOnce({ ...fixture('2026-10-03'), tasks: [] })
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' } })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('断网')
    expect(wrapper.find('.day-empty').exists()).toBe(false)
    await wrapper.get('[role="alert"] button').trigger('click'); await flushPromises()
    expect(wrapper.get('.day-empty').text()).toContain('还没有安排')
    wrapper.unmount()
  })
  it('后台较早请求不能覆盖写入后的刷新，成员切换丢弃迟到数据', async () => {
    let resolveOld!: (value: unknown) => void
    fetchDay.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve })).mockResolvedValueOnce({ ...fixture('2026-10-03'), tasks: [] })
    const store = useDayWorkspaceStore()
    const old = store.load('2026-10-03')
    await store.load('2026-10-03', true)
    resolveOld(fixture('2026-10-03')); await old
    expect(store.days['2026-10-03']!.tasks).toHaveLength(0)
    fetchDay.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const pending = store.load('2026-10-03', true)
    useSessionStore().accept({ accountId: 'other', member: { id: 'other', name: '其他', initial: '?', color: '', role: '' }, identities: [], createdAt: '' })
    await flushPromises(); resolveOld(fixture('2026-10-03')); await pending
    expect(store.days['2026-10-03']).toBeUndefined()
  })
})
