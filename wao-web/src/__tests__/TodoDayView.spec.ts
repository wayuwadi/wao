import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
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
  afterEach(() => vi.restoreAllMocks())
  beforeEach(() => {
    localStorage.clear(); sessionStorage.clear(); vi.clearAllMocks(); setActivePinia(createPinia())
    const data = useDataStore(); seedDataStore(data); data.today = '2026-10-03'
    useSessionStore().accept({ accountId: 'a', member: { id: 'wg', name: '验收', initial: '验', color: '#4358D6', role: '成员' }, identities: [], createdAt: '' })
  })
  const fixture = (date: string) => {
    const task = useDataStore().tasks[0]!
    return { date, recordingStartedAt: '2026-10-01T00:00:00+08:00', historyCoverage: 'RECORDED', tasks: [{ task: { ...task, title: '后来改名', status: '待办' }, snapshot: { ...task, title: '当天原名称', status: '已完成', start: date, subs: [{ id: 'one', text: '当天步骤', done: true }] }, source: 'RECORDED', planned: true, finished: true, events: [] }], notes: [], summary: { plannedCount: 1, finishedCount: 1, stepDoneCount: 1, noteCount: 0 } }
  }
  it('清空或只留空白立即清除标题草稿，重新输入生成新的提交标识', async () => {
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [] })
    const key = 'wao-day-task-draft-v1:a:wg:2026-10-03'
    const otherKey = 'wao-day-task-draft-v1:a:wg:2026-10-04'
    localStorage.setItem(otherKey, JSON.stringify({ id: 'other', title: '明天的事' }))
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' } }); await flushPromises()
    const input = wrapper.get('[aria-label="当天要做的事"]')
    await input.setValue('原标题')
    const id = JSON.parse(localStorage.getItem(key)!).id
    await input.setValue('')
    expect(localStorage.getItem(key)).toBeNull()
    await input.setValue('新标题')
    expect(JSON.parse(localStorage.getItem(key)!).id).not.toBe(id)
    await input.setValue('   ')
    expect(localStorage.getItem(key)).toBeNull()
    expect(JSON.parse(localStorage.getItem(otherKey)!).title).toBe('明天的事')
    wrapper.unmount()
    const restored = mount(TodoDayView, { props: { date: '2026-10-03' } }); await flushPromises()
    expect(restored.get<HTMLInputElement>('[aria-label="当天要做的事"]').element.value).toBe('')
    restored.unmount()
  })
  it('日期和成员切换恢复各自草稿，不让同步清空覆盖另一份草稿', async () => {
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [] })
    const drafts = [
      ['wao-day-task-draft-v1:a:wg:2026-10-03', { id: 'today', title: '今天的草稿' }],
      ['wao-day-task-draft-v1:a:wg:2026-10-04', { id: 'tomorrow', title: '明天的草稿' }],
      ['wao-day-task-draft-v1:other:other:2026-10-04', { id: 'member', title: '另一成员草稿' }],
    ] as const
    drafts.forEach(([key, draft]) => localStorage.setItem(key, JSON.stringify(draft)))
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' } }); await flushPromises()
    await wrapper.setProps({ date: '2026-10-04' }); await flushPromises()
    expect(wrapper.get<HTMLInputElement>('[aria-label="当天要做的事"]').element.value).toBe('明天的草稿')
    useSessionStore().accept({ accountId: 'other', member: { id: 'other', name: '其他', initial: '?', color: '', role: '' }, identities: [], createdAt: '' })
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('[aria-label="当天要做的事"]').element.value).toBe('另一成员草稿')
    drafts.forEach(([key, draft]) => expect(JSON.parse(localStorage.getItem(key)!)).toEqual(draft))
    wrapper.unmount()
  })
  it('项目请求先完成而快照尚未装入时，不清空草稿中的项目', async () => {
    const data = useDataStore(), projects = [...data.projects], project = projects[0]!.id
    const key = 'wao-day-task-draft-v1:a:wg:2026-10-03'
    localStorage.setItem(key, JSON.stringify({ id: 'draft', title: '继续填写', project }))
    data.projects = []; data.loadStates.projects = 'loading'; data.apiLive = false
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [] })
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' } }); await flushPromises()
    data.loadStates.projects = 'loaded'; await flushPromises()
    expect(JSON.parse(localStorage.getItem(key)!).project).toBe(project)
    data.projects = projects; data.apiLive = true; await flushPromises()
    expect(wrapper.get<HTMLSelectElement>('[aria-label="任务所属项目"]').element.value).toBe(project)
    data.projects = []; await flushPromises()
    expect(wrapper.get<HTMLSelectElement>('[aria-label="任务所属项目"]').element.value).toBe('')
    expect(JSON.parse(localStorage.getItem(key)!).title).toBe('继续填写')
    wrapper.unmount()
  })
  it('本机保存和清除失败保留当前输入并提供可执行的保护重试', async () => {
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [] })
    const key = 'wao-day-task-draft-v1:a:wg:2026-10-03'
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' } }); await flushPromises()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => { throw new Error('存储不可用') })
    await wrapper.get('[aria-label="当天要做的事"]').setValue('保留输入')
    expect(wrapper.get<HTMLInputElement>('[aria-label="当天要做的事"]').element.value).toBe('保留输入')
    expect(wrapper.text()).toContain('无法保护本机标题草稿')
    await wrapper.get('[role="alert"] button').trigger('click')
    expect(JSON.parse(localStorage.getItem(key)!).title).toBe('保留输入')
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementationOnce(() => { throw new Error('清除失败') })
    await wrapper.get('[aria-label="当天要做的事"]').setValue('')
    expect(wrapper.text()).toContain('本机旧草稿尚未清除')
    expect(wrapper.get<HTMLInputElement>('[aria-label="当天要做的事"]').element.value).toBe('')
    await wrapper.get('[role="alert"] button').trigger('click')
    expect(localStorage.getItem(key)).toBeNull()
    expect(wrapper.text()).not.toContain('重试保护草稿')
    wrapper.unmount()
  })
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
