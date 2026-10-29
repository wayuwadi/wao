import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ref } from 'vue'
const fetchDay = vi.hoisted(() => vi.fn())
vi.mock('@/api/dayWorkspace', async importOriginal => ({ ...await importOriginal<typeof import('@/api/dayWorkspace')>(), fetchDayWorkspace: fetchDay }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }) }))
import TodoView from '@/views/TodoLegacyView.vue'
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
    await wrapper.get('.todo-checklist button').trigger('click')
    expect(wrapper.get('.todo-checklist').text()).toContain('当天步骤')
    expect(wrapper.get('.todo-checklist input').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
  it('四象限仅使用当天记录，保留未收尾子项、日期和添加草稿，单独记住成员选择', async () => {
    const data = useDataStore(), base = data.tasks[0]!
    data.tasks = ['P0', 'P1', 'P2', 'P3'].map((pr, i) => ({ ...base, id: `q${i}`, title: `当日${pr}`, pr, status: '待办', subs: [] })) as typeof data.tasks
    data.tasks.push({ ...base, id: 'other-day', title: '其他日期', pr: 'P0' })
    data.tasks[1]!.status = '已完成'; data.tasks[1]!.subs = [{ id: 'pending', text: '还需验收', done: false, status: '待验证' }]
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: data.tasks.slice(0, 4).map(task => ({ task, snapshot: task, planned: task.id !== 'q3', events: [] })) })
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' }, global: { stubs: { TaskSchedulePopover: true } } }); await flushPromises()
    await wrapper.get('[aria-label="当天要做的事"]').setValue('未提交的标题')
    const draft = localStorage.getItem('wao-day-task-draft-v1:a:wg:2026-10-03')
    await wrapper.findAll('.day-view-switch button')[1]!.trigger('click')
    for (const pr of ['P0', 'P1', 'P2', 'P3']) expect(wrapper.get(`[data-group="${pr}"]`).text()).toContain(`当日${pr}`)
    expect(wrapper.text()).not.toContain('其他日期')
    expect(wrapper.get('[data-group="P1"]').text()).toContain('还需验收')
    expect(wrapper.get('[data-group="P3"]').text()).toContain('当天留下了进展')
    expect(wrapper.get<HTMLInputElement>('[aria-label="选择待办日期"]').element.value).toBe('2026-10-03')
    expect(wrapper.get<HTMLInputElement>('[aria-label="当天要做的事"]').element.value).toBe('未提交的标题')
    expect(localStorage.getItem('wao-day-task-draft-v1:a:wg:2026-10-03')).toBe(draft)
    expect(localStorage.getItem('wao-todo-day-view-v1:a:wg')).toBe('quadrant')
    expect(localStorage.getItem('wao-todo-view-v1')).toBeNull()
    wrapper.unmount()
    const restored = mount(TodoDayView, { props: { date: '2026-10-03' } }); await flushPromises()
    expect(restored.find('.day-quadrants').exists()).toBe(true)
    useSessionStore().accept({ accountId: 'other', member: { id: 'other', name: '其他', initial: '?', color: '', role: '' }, identities: [], createdAt: '' }); await flushPromises()
    expect(restored.find('.day-quadrants').exists()).toBe(false)
    restored.unmount()
  })
  it('四象限调整优先级走串行保存，失败选择留在新象限且可处理保存', async () => {
    const data = useDataStore(), task = data.tasks[0]!
    task.pr = 'P0'; task.status = '进行中'; task.subs = []
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [{ task, snapshot: task, planned: true, events: [] }] })
    const patch = vi.spyOn(data, 'patchTask')
    const flush = vi.spyOn(data, 'flushTask').mockResolvedValue(undefined)
    vi.spyOn(data, 'taskSaveState').mockReturnValue('error')
    vi.spyOn(data, 'taskSaveError').mockReturnValue('保存失败，选择仍保留')
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' }, global: { stubs: { TaskSchedulePopover: true } } }); await flushPromises()
    await wrapper.findAll('.day-view-switch button')[1]!.trigger('click')
    await wrapper.get('.task-priority-select').setValue('P3'); await flushPromises()
    expect(patch).toHaveBeenCalledWith(task.id, { pr: 'P3' })
    expect(flush).toHaveBeenCalledWith(task.id)
    expect(wrapper.get('[data-group="P3"] .task-error').text()).toContain('保存失败')
    expect(wrapper.get<HTMLSelectElement>('.task-priority-select').element.value).toBe('P3')
    expect(wrapper.get('[data-group="P0"]').text()).toContain('这个象限没有当天待办')
    wrapper.unmount()
  })
  const dragFixture = async () => {
    const data = useDataStore(), task = data.tasks[0]!
    task.pr = 'P0'; task.status = '进行中'; task.subs = []
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [{ task, snapshot: task, planned: true, events: [] }] })
    localStorage.setItem('wao-todo-day-view-v1:a:wg', 'quadrant')
    const patch = vi.spyOn(data, 'patchTask'), flush = vi.spyOn(data, 'flushTask').mockResolvedValue(undefined)
    const saveState = ref<'idle' | 'error' | 'saving'>('idle')
    vi.spyOn(data, 'taskSaveState').mockImplementation(() => saveState.value)
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' }, global: { stubs: { TaskSchedulePopover: true, NoteComposer: true } } }); await flushPromises()
    const transfer = { effectAllowed: '', dropEffect: '', setData: vi.fn() }
    return { data, task, wrapper, patch, flush, transfer, saveState }
  }
  it('任务标题可拖到四个象限及空象限，自动调整优先级并保存', async () => {
    const { task, wrapper, patch, flush, transfer } = await dragFixture()
    for (const priority of ['P1', 'P3', 'P2', 'P0']) {
      await wrapper.get('.task-title').trigger('dragstart', { dataTransfer: transfer })
      await wrapper.get(`[data-group="${priority}"] h3`).trigger('dragover', { dataTransfer: transfer })
      expect(wrapper.get(`[data-group="${priority}"]`).classes()).toContain('drop-target')
      await wrapper.get(`[data-group="${priority}"] h3`).trigger('drop', { dataTransfer: transfer }); await flushPromises()
      expect(wrapper.get(`[data-group="${priority}"] .task-title`).text()).toBe(task.title)
      expect(patch).toHaveBeenLastCalledWith(task.id, { pr: priority })
      expect(flush).toHaveBeenLastCalledWith(task.id)
      expect(wrapper.find('.drop-target').exists()).toBe(false)
    }
    expect(transfer.setData).toHaveBeenCalledWith('application/x-wao-day-task', task.id)
    wrapper.unmount()
  })
  it('同格释放、取消和外部编号释放不保存，内部进展编辑区不接受任务释放', async () => {
    const { wrapper, patch, flush, transfer } = await dragFixture()
    await wrapper.get('[data-group="P3"]').trigger('drop', { dataTransfer: transfer })
    await wrapper.get('.task-drag-handle').trigger('dragstart', { dataTransfer: transfer })
    await wrapper.get('[data-group="P0"]').trigger('drop', { dataTransfer: transfer })
    await wrapper.get('.task-title').trigger('dragstart', { dataTransfer: transfer })
    await wrapper.get('.task-title').trigger('dragend')
    await wrapper.get('[data-group="P3"]').trigger('drop', { dataTransfer: transfer })
    await wrapper.get('.task-title').trigger('dragstart', { dataTransfer: transfer })
    await wrapper.get('.task-priority-select').trigger('drop', { dataTransfer: transfer })
    await flushPromises()
    expect(patch).not.toHaveBeenCalled(); expect(flush).not.toHaveBeenCalled()
    expect(wrapper.find('[data-group="P0"] .task-title').exists()).toBe(true)
    wrapper.unmount()
  })
  it('失败保留新象限与原地重试，保存中不能发起或完成拖动', async () => {
    const { data, task, wrapper, patch, flush, transfer, saveState } = await dragFixture()
    saveState.value = 'error'
    vi.spyOn(data, 'taskSaveError').mockReturnValue('保存失败，选择仍保留')
    await wrapper.get('.task-title').trigger('dragstart', { dataTransfer: transfer })
    await wrapper.get('[data-group="P3"]').trigger('drop', { dataTransfer: transfer }); await flushPromises()
    expect(wrapper.get('[data-group="P3"] [role="alert"]').text()).toContain('保存失败')
    await wrapper.findAll('[role="alert"] button').find(item => item.text() === '重试保存')!.trigger('click')
    expect(flush).toHaveBeenCalledTimes(2)
    expect(flush).toHaveBeenLastCalledWith(task.id)
    await wrapper.get('.task-title').trigger('dragstart', { dataTransfer: transfer })
    saveState.value = 'saving'; await flushPromises()
    expect(wrapper.get('.task-title').attributes('draggable')).toBe('false')
    await wrapper.get('[data-group="P1"]').trigger('drop', { dataTransfer: transfer })
    expect(patch).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
  it('日期、布局、成员切换后旧拖动失效，历史任务只读', async () => {
    const { wrapper, patch, transfer } = await dragFixture()
    await wrapper.get('.task-title').trigger('dragstart', { dataTransfer: transfer })
    await wrapper.setProps({ date: '2026-10-04' }); await flushPromises()
    await wrapper.get('[data-group="P3"]').trigger('drop', { dataTransfer: transfer })
    await wrapper.get('.task-title').trigger('dragstart', { dataTransfer: transfer })
    await wrapper.findAll('.day-view-switch button')[0]!.trigger('click')
    await wrapper.findAll('.day-view-switch button')[1]!.trigger('click')
    await wrapper.get('[data-group="P3"]').trigger('drop', { dataTransfer: transfer })
    await wrapper.get('.task-title').trigger('dragstart', { dataTransfer: transfer })
    useSessionStore().accept({ accountId: 'other', member: { id: 'other', name: '其他', initial: '?', color: '', role: '' }, identities: [], createdAt: '' }); await flushPromises()
    await wrapper.findAll('.day-view-switch button')[1]!.trigger('click'); await flushPromises()
    await wrapper.get('[data-group="P3"]').trigger('drop', { dataTransfer: transfer })
    await wrapper.setProps({ date: '2026-10-02' }); await flushPromises()
    expect(wrapper.get('.task-title').attributes('draggable')).toBe('false')
    expect(wrapper.find('.task-drag-handle').exists()).toBe(false)
    expect(patch).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('历史四象限保留当日快照且不提供优先级修改，本机存储失败不阻断切换', async () => {
    fetchDay.mockResolvedValue(fixture('2026-10-02'))
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-02' } }); await flushPromises()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => { throw new Error('只读存储') })
    await wrapper.findAll('.day-view-switch button')[1]!.trigger('click')
    expect(wrapper.find('.day-quadrants').exists()).toBe(true)
    expect(wrapper.text()).toContain('按任务现有优先级分组')
    expect(wrapper.get('.task-title').text()).toBe('当天原名称')
    expect(wrapper.find('.task-priority-select').exists()).toBe(false)
    expect(wrapper.text()).toContain('无法记住本机视图')
    wrapper.unmount()
  })
  it('今日安排与未排期进展、已收尾分开，未收尾子项不能被完成分组隐藏', async () => {
    const data = useDataStore(), base = data.tasks[0]!
    data.tasks = [
      { ...base, id: 'planned', title: '今天要做', status: '进行中', subs: [] },
      { ...base, id: 'trace', title: '当天进展未安排', status: '进行中', subs: [] },
      { ...base, id: 'done', title: '真正收尾', status: '已完成', subs: [] },
      { ...base, id: 'pending-sub', title: '仍需子项验收', status: '已完成', subs: [{ text: '仍待验证', done: false, status: '待验证' }] },
    ] as typeof data.tasks
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: data.tasks.map(task => ({ task, snapshot: task, planned: task.id !== 'trace', finished: task.id === 'done', events: [] })) })
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' }, global: { stubs: { TaskSchedulePopover: true } } })
    await flushPromises()
    const planned = wrapper.get('[data-group="planned"]'), progress = wrapper.get('[data-group="progress"]'), records = wrapper.get('[data-group="records"]')
    expect(planned.findAll('.task-title').map(item => item.text())).toEqual(['今天要做', '仍需子项验收'])
    expect(progress.findAll('.task-title').map(item => item.text())).toEqual(['当天进展未安排'])
    expect(progress.get('.task-title').isVisible()).toBe(true)
    expect(records.findAll('.task-title').map(item => item.text())).toEqual(['真正收尾'])
    expect(planned.get('.todo-checklist').isVisible()).toBe(true)
    expect(planned.get('.todo-checklist').text()).toContain('仍待验证')
    expect(wrapper.get('.day-overview').text()).toContain('3 件待办')
    const recordsContent = records.get('[id]')
    expect((recordsContent.element as HTMLElement).style.display).toBe('none')
    await records.get('.records-toggle').trigger('click')
    expect(records.get('.records-toggle').attributes('aria-expanded')).toBe('true')
    expect((recordsContent.element as HTMLElement).style.display).toBe('')
    expect(wrapper.get('.day-secondary').find('[aria-label="每日固定事项"]').exists()).toBe(true)
    expect(wrapper.get('.day-secondary').find('.day-notes').exists()).toBe(true)
    wrapper.unmount()
  })
  it('默认展开未完成子事项，更多菜单记进展沿用隔离草稿；日期切换恢复展开', async () => {
    const data = useDataStore(), task = data.tasks[0]!
    task.status = '进行中'; task.subs = [{ id: 'pending', text: '明确下一步', done: false }]
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [{ task, snapshot: task, planned: true, finished: false, events: [{ id: 1, summary: '改了安排', occurredAt: '2026-10-03T09:00:00+08:00' }] }] })
    const wrapper = mount(TodoDayView, { attachTo: document.body, props: { date: '2026-10-03' }, global: { stubs: { TaskSchedulePopover: true, NoteComposer: true } } })
    await flushPromises()
    expect(wrapper.get('.todo-checklist').isVisible()).toBe(true)
    expect(wrapper.find('.task-actions').exists()).toBe(false)
    expect(wrapper.get('.latest-progress').text()).toContain('改了安排')
    const menuAction = async (label: string) => {
      await wrapper.get('.task-more').trigger('click'); await flushPromises()
      const action = [...document.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')].find(item => item.textContent === label)!
      action.click(); await flushPromises()
    }
    await menuAction('记进展')
    expect(wrapper.getComponent({ name: 'NoteComposer' }).props('draftScope')).toBe(`day:a:wg:2026-10-03:${task.id}`)
    await wrapper.get('.task-note-composer button').trigger('click')
    await menuAction('收起子事项')
    expect(wrapper.get('.todo-checklist').isVisible()).toBe(false)
    expect(wrapper.get('.checklist-reopen').text()).toContain('1 项子事项')
    await wrapper.setProps({ date: '2026-10-04' }); await flushPromises()
    expect(wrapper.get('.todo-checklist').isVisible()).toBe(true)
    expect(wrapper.get('.todo-checklist input').attributes('disabled')).toBeDefined()
    await wrapper.get('.task-more').trigger('click'); await flushPromises()
    expect([...document.querySelectorAll('[role="menuitem"]')].map(item => item.textContent)).not.toContain('记进展')
    wrapper.unmount(); document.body.innerHTML = ''
  })
  it('子事项勾选使用既有状态保存，不把待验证或主任务自动标成完成', async () => {
    const data = useDataStore(), task = data.tasks[0]!
    task.status = '进行中'; task.subs = [{ id: 'pending', text: '需要验收', done: false, status: '待验证' }]
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [{ task, snapshot: task, planned: true, finished: false, events: [] }] })
    const setState = vi.spyOn(data, 'setSubState')
    const flush = vi.spyOn(data, 'flushTask').mockResolvedValue(undefined)
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' }, global: { stubs: { TaskSchedulePopover: true } } }); await flushPromises()
    expect(wrapper.get('.todo-checklist .sub-state').text()).toBe('待验证')
    expect(setState).not.toHaveBeenCalled()
    await wrapper.get('.todo-checklist input').setValue(true); await flushPromises()
    expect(setState).toHaveBeenCalledWith(task.id, 'pending', '已完成')
    expect(flush).toHaveBeenCalledWith(task.id)
    expect(task.status).toBe('进行中')
    expect(wrapper.find('[data-group="progress"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it('收起进展分组时保留保存失败反馈，历史分组默认可读', async () => {
    const data = useDataStore(), base = data.tasks[0]!
    data.tasks = [{ ...base, status: '已完成', subs: [] }]
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [{ task: data.tasks[0], snapshot: data.tasks[0], planned: false, finished: true, events: [] }] })
    vi.spyOn(data, 'taskSaveState').mockReturnValue('error')
    vi.spyOn(data, 'taskSaveError').mockReturnValue('保存尚未确认')
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' }, global: { stubs: { TaskSchedulePopover: true } } })
    await flushPromises()
    expect(wrapper.get('[data-group="records"] [role="alert"]').isVisible()).toBe(true)
    await wrapper.get('.records-toggle').trigger('click')
    expect(wrapper.get('[data-group="records"] [role="alert"]').isVisible()).toBe(true)
    wrapper.unmount()
  })
  it('右侧小记先显示三条，用户能展开全部并收起', async () => {
    const notes = Array.from({ length: 5 }, (_, index) => ({ docId: `note-${index}`, title: `记录${index}`, createdAt: '2026-10-03T09:00:00+08:00', updatedAt: '2026-10-03T09:00:00+08:00', late: false }))
    fetchDay.mockResolvedValue({ ...fixture('2026-10-03'), tasks: [], notes })
    const wrapper = mount(TodoDayView, { props: { date: '2026-10-03' } }); await flushPromises()
    const visibleNotes = () => wrapper.findAll('.day-note-link').filter(item => (item.element as HTMLElement).style.display !== 'none')
    expect(visibleNotes()).toHaveLength(3)
    expect(wrapper.get('.day-side-rail button:last-child').attributes('aria-label')).toBe('随手记，5 条记录')
    await wrapper.get('.notes-more').trigger('click')
    expect(visibleNotes()).toHaveLength(5)
    expect(wrapper.get('.day-side-rail button:last-child').attributes('aria-label')).toBe('随手记，5 条记录')
    await wrapper.get('.notes-more').trigger('click')
    expect(visibleNotes()).toHaveLength(3)
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
