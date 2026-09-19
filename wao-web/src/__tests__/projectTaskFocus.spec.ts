import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
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
