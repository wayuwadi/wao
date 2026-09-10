import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
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
    expect(wrapper.findAll('.board-col')).toHaveLength(6)
    await wrapper.get('.empty-columns-control button[aria-expanded]').trigger('click')
    await wrapper.get('.board-card').trigger('dragstart')
    expect(wrapper.findAll('.board-col')).toHaveLength(2)
    expect(wrapper.findAll('.empty-column-target')).toHaveLength(4)
    await wrapper.get('.board-card').trigger('dragend')
    expect(wrapper.findAll('.board-col')).toHaveLength(2)
    wrapper.unmount()
  })

  it('按派生交付阶段展示真实数量并用 URL 恢复筛选', async () => {
    const data = useDataStore()
    data.taskById('t1')!.status = '进行中'
    data.taskById('t2')!.status = '收集中'
    data.taskById('t3')!.status = '待验证'
    data.taskById('t12')!.status = '已完成'
    await router.push('/projects/p1?view=board')
    await router.isReady()
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()

    const filters = wrapper.findAll('.delivery-filter-button')
    expect(filters.map((button) => button.text())).toEqual([
      '全部阶段 6',
      '方向探索 1',
      '待开发 3',
      '待验收 1',
      '已验证 1',
    ])

    await filters[3]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.stage).toBe('acceptance')
    expect(wrapper.findAll('.board-col')).toHaveLength(1)
    expect(wrapper.get('.board-col-label').text()).toBe('待验证')
    expect(wrapper.text()).toContain(data.taskById('t3')!.title)
    expect(wrapper.text()).not.toContain(data.taskById('t2')!.title)

    wrapper.unmount()
    await router.push('/projects/p1?view=board&stage=acceptance')
    const restored = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    expect(restored.get('.delivery-filter-button.on').text()).toContain('待验收')
    expect(restored.findAll('.board-col')).toHaveLength(1)
    restored.unmount()
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
