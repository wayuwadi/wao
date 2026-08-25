import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { createAiRuns } from '@/api/ai'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { useToastHost } from '@/composables/toast'
import type { AiRun } from '@/types'

vi.mock('@/api/ai', () => ({
  createAiRuns: vi.fn<() => Promise<unknown[]>>(),
  fetchTaskAiRuns: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
}))

describe('项目任务合并为一次 AI Run', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore()
    useToastHost().items.splice(0)
    vi.mocked(createAiRuns).mockReset()
  })

  it('把所选任务作为一个上下文派发，并同步更新全部任务状态', async () => {
    const data = useDataStore()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:id', component: { template: '<div />' } }],
    })
    await router.push('/projects/p1?view=list')
    await router.isReady()
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.find('.select-all').exists()).toBe(false)
    expect(wrapper.find('.batch-ai').exists()).toBe(false)
    const selectable = wrapper.findAll('.task-list-card .select-dot')
    expect(selectable.length).toBeGreaterThanOrEqual(2)

    await selectable[0]!.trigger('click')
    expect(wrapper.get('.batch-ai').text()).toContain('合并给 AI · 1')
    await selectable[1]!.trigger('click')
    const selectedIds = data.tasksByProject('p1').filter((task) => task.status !== '已完成')
      .slice(0, 2).map((task) => task.id)
    vi.mocked(createAiRuns).mockResolvedValueOnce([{
      id: 'ar-merged', taskId: selectedIds[0], taskIds: selectedIds,
    } as AiRun])

    expect(wrapper.get('.batch-ai').text()).toContain('合并给 AI · 2')
    await wrapper.get('.batch-ai').trigger('click')
    await flushPromises()

    expect(createAiRuns).toHaveBeenCalledWith(selectedIds, { mergeTasks: true })
    expect(selectedIds.map((id) => data.taskById(id)?.status)).toEqual(['AI 进行中', 'AI 进行中'])
    expect(useToastHost().items.some((item) => item.text.includes('2 个任务合并为 1 次 AI Run'))).toBe(true)
    wrapper.unmount()
  })

  it('在列表状态胶囊中选择后直接写透任务状态', async () => {
    const data = useDataStore()
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:id', component: { template: '<div />' } }],
    })
    await router.push('/projects/p1?view=list')
    await router.isReady()
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    const firstTask = data.tasksByProject('p1').find((task) => task.status !== '已完成')!

    await wrapper.findAll('.task-list-card .task-status-trigger')[0]!.trigger('click')
    const options = wrapper.findAll('.task-status-menu button')
    const verifying = options.find((option) => option.text().includes('待验证'))
    expect(verifying).toBeDefined()
    await verifying!.trigger('click')
    await flushPromises()

    expect(data.taskById(firstTask.id)?.status).toBe('待验证')
    expect(data.taskSaveState(firstTask.id)).toBe('saved')
    expect(useToastHost().items.some((item) => item.text.includes('状态已改为「待验证」'))).toBe(true)
    wrapper.unmount()
  })
})
