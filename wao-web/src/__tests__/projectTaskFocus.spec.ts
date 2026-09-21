import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import TaskQuadrantView from '@/components/task/TaskQuadrantView.vue'
import { useSessionStore } from '@/stores/session'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { useUiStore } from '@/stores/ui'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import projectDetailSource from '@/views/ProjectDetailView.vue?raw'

vi.mock('@/api/ai', () => ({ createAiRuns: vi.fn<() => Promise<never[]>>().mockResolvedValue([]) }))

const router = createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/projects/:id', component: { template: '<div/>' } },
    { path: '/:p(.*)*', component: { template: '<div/>' } },
  ],
})

describe('项目页聚焦任务', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore(useDataStore())
  })

  it('项目四象限复用待办组件，筛选限制当前项目，新建预选项目与优先级', async () => {
    const data = useDataStore()
    const tasks = data.tasksByProject('p1')
    const source = tasks[0]!
    data.tasks = [
      { ...source, id: 'inside', title: '项目内任务', project: 'p1', pr: 'P0', status: '待办', owner: 'wg', createdBy: 'wg', subs: [] },
      { ...source, id: 'filtered', title: '筛选外任务', project: 'p1', pr: 'P1', status: '待办', owner: 'wd', createdBy: 'wd', subs: [] },
      { ...source, id: 'outside', title: '项目外任务', project: 'p2', pr: 'P2', status: '待办', owner: 'wg', createdBy: 'wg', subs: [] },
      { ...source, id: 'done', title: '已完成任务', project: 'p1', pr: 'P3', status: '已完成', owner: 'wg', createdBy: 'wg', subs: [] },
    ]
    useSessionStore().members = [
      { id: 'wg', name: '娃哥', initial: '哥', color: '#446' },
      { id: 'wd', name: '娃弟', initial: '弟', color: '#664' },
    ] as ReturnType<typeof useSessionStore>['members']
    await router.push('/projects/p1')
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    await wrapper.findAll('.seg-btn').find(button => button.text() === '四象限')!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.view).toBe('quadrant')
    expect(wrapper.findComponent(TaskQuadrantView).exists()).toBe(true)
    expect(wrapper.findAll('.quadrant')).toHaveLength(4)
    expect(wrapper.findAll('.quadrant-title').map(node => node.text())).toEqual(['项目内任务', '筛选外任务'])
    useUiStore().setOwnerFilter('p1', 'wg')
    await flushPromises()
    expect(wrapper.findAll('.quadrant-title').map(node => node.text())).toEqual(['项目内任务'])
    useUiStore().setOwnerFilter('p1', '')
    useUiStore().setCreatorFilter('p1', 'wg')
    await flushPromises()
    expect(wrapper.findAll('.quadrant-title').map(node => node.text())).toEqual(['项目内任务'])
    await wrapper.get('[aria-label="在重要不紧急新建任务"]').trigger('click')
    expect(useUiStore().modalProject).toBe('p1')
    expect(useUiStore().modalPriority).toBe('P1')
    await wrapper.get('.quadrant-toolbar button').trigger('click')
    expect(wrapper.findAll('.quadrant-title').map(node => node.text())).toEqual(['项目内任务', '已完成任务'])
    await wrapper.get('.batch-selection-toggle').trigger('click')
    await wrapper.findAll('.copy-selection-actions button').find(button => button.text().startsWith('全选当前可见'))!.trigger('click')
    expect(wrapper.get('.selection-count').text()).toContain('2')
    await wrapper.get('.quadrant-toolbar button').trigger('click')
    expect(wrapper.get('.selection-count').text()).toContain('1')
    wrapper.unmount()
    const refreshed = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    expect(refreshed.findComponent(TaskQuadrantView).exists()).toBe(true)
    expect(refreshed.findAll('.quadrant-title').map(node => node.text())).toEqual(['项目内任务'])
    refreshed.unmount()
  })

  it('表格实际拖放形成任务树，折叠和移回顶层入口可用', async () => {
    const data = useDataStore(), tasks = data.tasksByProject('p1')
    const parent = tasks[0]!, child = tasks[1]!
    const move = vi.spyOn(data, 'moveTask').mockImplementation(async (id, parentId) => { data.taskById(id)!.parentTaskId = parentId })
    await router.push('/projects/p1?view=list')
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    const children = () => wrapper.findAll('.task-row').filter(row => row.text().includes(child.title))
    const transfer = { setData: vi.fn(), getData: () => '', effectAllowed: '' }
    await children()[0]!.trigger('dragstart', { dataTransfer: transfer })
    const parentRow = wrapper.findAll('.task-row').find(row => row.text().includes(parent.title))!
    await parentRow.trigger('drop', { dataTransfer: transfer, clientY: 0 }); await flushPromises()
    expect(move).toHaveBeenCalledWith(child.id, parent.id)
    const toggle = wrapper.get(`[aria-label="展开或收起 ${parent.title} 的子任务"]`)
    expect(children()).toHaveLength(1)
    await toggle.trigger('click'); expect(children()).toHaveLength(0)
    await toggle.trigger('click'); expect(children()).toHaveLength(1)
    await children()[0]!.get('[aria-label="移回顶层"]').trigger('click'); await flushPromises()
    expect(move).toHaveBeenCalledWith(child.id, null)
    expect(children()).toHaveLength(1)
    wrapper.unmount(); move.mockRestore()
  })

  it('加载失败不会冒充空项目，重试期间继续保留已显示任务', async () => {
    const data = useDataStore()
    const originalTasks = [...data.tasks]
    data.tasks = []
    data.taskLoadState = 'error'
    data.taskLoadError = 'timeout of 10000ms exceeded'
    const reload = vi.spyOn(data, 'hydrate').mockImplementation(async () => {
      data.taskLoadState = 'loading'
    })
    await router.push('/projects/p1?view=list')
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.text()).toContain('任务加载失败')
    expect(wrapper.text()).not.toContain('还没有任务')
    await wrapper.get('[role="alert"] button').trigger('click')
    expect(reload).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('正在加载任务')
    expect(wrapper.text()).not.toContain('还没有任务')
    data.tasks = originalTasks
    await flushPromises()
    expect(wrapper.findAll('.task-row').length).toBeGreaterThan(0)
    wrapper.unmount()
    reload.mockRestore()
  })

  it('普通入口始终默认表格，忽略旧看板偏好，仍允许手动切换', async () => {
    useUiStore().setTaskView('p1', 'board')
    await router.push('/projects/p1')
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.find('table[aria-label="项目任务表格"]').exists()).toBe(true)
    const boardButton = wrapper.findAll('.seg-btn').find((button) => button.text().includes('看板'))!
    await boardButton.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.view).toBe('board')
    expect(wrapper.find('.board').exists()).toBe(true)
    await router.push('/projects/p1')
    await flushPromises()
    expect(wrapper.find('table[aria-label="项目任务表格"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('项目表格保留固定列和可展开的子任务', async () => {
    const task = useDataStore().taskById('t1')!
    task.number = 0
    task.subs = [{ id: 'stable-sub', text: '固定子任务', done: false, number: 13 }]
    await router.push('/projects/p1?view=list')
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.get('table').attributes('aria-label')).toBe('项目任务表格')
    expect(wrapper.findAll('thead th').map((node) => node.text())).toEqual([
      'ID',
      '标题',
      '分组',
      '优先级',
      '状态',
      '负责人',
      '创建者',
      '创建日期',
      '计划开始',
      '截止日期',
    ])
    const row = wrapper.findAll('.task-row').find((node) => node.text().includes(task.title))!
    expect(row.get('.table-number').text()).toBe('0')
    expect(row.find('.work-item-icon.task').exists()).toBe(true)
    expect(row.get('.sub-count').text()).toBe('0/1')
    expect(wrapper.find('.table-subs .work-item-icon.subtask').exists()).toBe(true)
    expect(wrapper.text()).toContain('固定子任务')
    await row.get('.tree-toggle').trigger('click')
    expect(wrapper.text()).not.toContain('固定子任务')
    await row.get('.tree-toggle').trigger('click')
    expect(wrapper.get('.sub-number').text()).toBe('13')
    wrapper.unmount()
  })

  it('项目头部之后直接展示任务页签，不再展示项目迭代概览', async () => {
    await router.push('/projects/p1')
    await router.isReady()
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('.project-loop').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('项目最终目标')
    expect(wrapper.text()).not.toContain('当前迭代')
    expect(wrapper.get('.ptabs').text()).toContain('任务')
    expect(wrapper.find('.tab-toolbar').exists()).toBe(true)
    wrapper.unmount()
  })

  it('批量操作按需显示，空列可展开且拖拽时保留全部目标状态', async () => {
    const data = useDataStore()
    data.tasks.forEach((task) => {
      if (task.project === 'p1') task.status = '待办'
    })
    await router.push('/projects/p1?view=board')
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.find('.copy-selection-actions').exists()).toBe(false)
    expect(wrapper.findAll('.board-col')).toHaveLength(2)
    await wrapper.get('.batch-selection-toggle').trigger('click')
    await wrapper.get('.select-dot').trigger('click')
    expect(wrapper.get('.selection-count').text()).toBe('已选 1 项')
    await wrapper
      .findAll('.copy-selection-actions > button')
      .find((button) => button.text() === '完成选择')!
      .trigger('click')
    expect(wrapper.find('.copy-selection-actions').exists()).toBe(false)
    await wrapper.get('.empty-columns-control button[aria-expanded]').trigger('click')
    // AI Run 下线后「AI 进行中」列不再出现
    expect(wrapper.findAll('.board-col')).toHaveLength(5)
    expect(wrapper.findAll('.board-col-label').map((node) => node.text())).not.toContain('AI 进行中')
    await wrapper.get('.empty-columns-control button[aria-expanded]').trigger('click')
    await wrapper.get('.board-card').trigger('dragstart')
    expect(wrapper.findAll('.board-col')).toHaveLength(2)
    expect(wrapper.findAll('.empty-column-target')).toHaveLength(3)
    await wrapper.get('.board-card').trigger('dragend')
    expect(wrapper.findAll('.board-col')).toHaveLength(2)
    wrapper.unmount()
  })

  it('交付阶段筛选按钮已去掉，旧链接里的 stage 参数不再隐藏任务', async () => {
    const data = useDataStore()
    data.taskById('t1')!.status = '进行中'
    data.taskById('t2')!.status = '收集中'
    data.taskById('t3')!.status = '待验证'
    await router.push('/projects/p1?view=board&stage=acceptance')
    await router.isReady()
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('.delivery-filter').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('全部阶段')
    expect(wrapper.text()).toContain(data.taskById('t2')!.title)
    expect(wrapper.text()).toContain(data.taskById('t3')!.title)
    wrapper.unmount()
  })

  it('项目头部不再显示项目状态，表格视图没有批量选择入口', async () => {
    await router.push('/projects/p1?view=list')
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.find('.phead-status').exists()).toBe(false)
    expect(wrapper.find('.batch-selection-toggle').exists()).toBe(false)
    expect(wrapper.get('.task-view-toolbar').text()).toContain('不分组')
    wrapper.unmount()
  })

  it('项目看板卡片中的连续长文本不会撑出状态列', async () => {
    const data = useDataStore()
    const task = data.tasksByProject('p1')[0]!
    task.status = '待办'
    task.title = `https://example.com/${'continuous-segment-'.repeat(30)}`
    await router.push('/projects/p1?view=board')
    await router.isReady()
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()

    const card = wrapper.findAll('.board-card').find((item) => item.text().includes(task.title))!
    const title = card.get('.bc-title')
    expect(title.text()).toBe(task.title)
    expect(projectDetailSource).toMatch(/\.board-card\s*{[^}]*max-width:\s*100%/s)
    expect(projectDetailSource).toMatch(/\.bc-title\s*{[^}]*overflow-wrap:\s*anywhere/s)
    wrapper.unmount()
  })
})
