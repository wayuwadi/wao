import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
const fetchDay = vi.hoisted(() => vi.fn())
vi.mock('@/api/dayWorkspace', async importOriginal => ({ ...await importOriginal<typeof import('@/api/dayWorkspace')>(), fetchDayWorkspace: fetchDay }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }) }))
import TodoView from '@/views/TodoView.vue'
import TodoDayView from '@/components/todo/TodoDayView.vue'
import { useDataStore } from '@/stores/data'
import { usePreferenceStore } from '@/stores/preferences'
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
  it('有效默认项目跨日期带入，现有日期草稿优先；失效选择清空但标题保留', async () => {
    const data = useDataStore(), first = data.projects[0]!.id, second = data.projects[1]!.id
    localStorage.setItem('wao-day-task-recent-project-v1:a:wg', first)
    localStorage.setItem('wao-day-task-draft-v1:a:wg:2026-10-04', JSON.stringify({ id: 'draft', title: '继续填写', project: second }))
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [] })
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' } })
    await flushPromises()
    expect(wrapper.get<HTMLSelectElement>('[aria-label="任务所属项目"]').element.value).toBe(first)
    await wrapper.setProps({ date: '2026-10-04' })
    await flushPromises()
    expect(wrapper.get<HTMLSelectElement>('[aria-label="任务所属项目"]').element.value).toBe(second)
    data.projects = data.projects.filter(project => project.id !== second)
    await flushPromises()
    expect(wrapper.get<HTMLSelectElement>('[aria-label="任务所属项目"]').element.value).toBe('')
    expect(wrapper.get<HTMLInputElement>('[aria-label="当天要做的事"]').element.value).toBe('继续填写')
    wrapper.unmount()
  })

  it('首次读取项目后恢复有效默认，成员切换不沿用其他人的选择', async () => {
    const data = useDataStore(), projects = [...data.projects]
    localStorage.setItem('wao-day-task-recent-project-v1:a:wg', projects[0]!.id)
    data.projects = []; data.loadStates.projects = 'loading'
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [] })
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' } })
    await flushPromises()
    data.projects = projects; data.loadStates.projects = 'loaded'
    await flushPromises()
    expect(wrapper.get<HTMLSelectElement>('[aria-label="任务所属项目"]').element.value).toBe(projects[0]!.id)
    useSessionStore().accept({ accountId: 'other', member: { id: 'other', name: '其他', initial: '?', color: '', role: '' }, identities: [], createdAt: '' })
    await flushPromises()
    expect(wrapper.get<HTMLSelectElement>('[aria-label="任务所属项目"]').element.value).toBe('')
    wrapper.unmount()
  })
  it('指定默认优先于最近项目，迟到默认不能覆盖手选和输入中的归属', async () => {
    const data = useDataStore(), prefs = usePreferenceStore()
    const first = data.projects[0]!.id, second = data.projects[1]!.id
    localStorage.setItem('wao-day-task-recent-project-v1:a:wg', first)
    prefs.defaultProject = second
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [] })
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' } }); await flushPromises()
    const select = wrapper.get<HTMLSelectElement>('[aria-label="任务所属项目"]')
    expect(select.element.value).toBe(second)
    await select.setValue(first); prefs.defaultProject = first; await flushPromises()
    prefs.defaultProject = second; await flushPromises()
    expect(select.element.value).toBe(first)
    await wrapper.get('[aria-label="当天要做的事"]').setValue('正在写的标题')
    prefs.defaultProject = first; await flushPromises()
    expect(wrapper.get<HTMLInputElement>('[aria-label="当天要做的事"]').element.value).toBe('正在写的标题')
    expect(select.element.value).toBe(first); wrapper.unmount()
  })

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

vi.mock('@/api/favorites', async original => ({ ...await original<typeof import('@/api/favorites')>(), fetchPreference: vi.fn().mockResolvedValue(''), savePreference: vi.fn().mockResolvedValue(undefined) }))
