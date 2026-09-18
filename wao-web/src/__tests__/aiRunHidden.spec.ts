import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import TaskDrawer from '@/components/task/TaskDrawer.vue'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { AI_RUN_ENABLED, selectableTaskStatuses } from '@/config/features'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { seedDataStore } from './helpers/seedDataStore'

enableAutoUnmount(afterEach)

vi.mock('@/api/ai', () => ({
  createAiRuns: vi.fn<() => Promise<never[]>>().mockResolvedValue([]),
  fetchTaskAiRuns: vi.fn<() => Promise<never[]>>().mockResolvedValue([]),
  pauseAiRun: vi.fn<() => Promise<Record<string, never>>>().mockResolvedValue({}),
}))

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/projects/:id', component: { template: '<div />' } },
      { path: '/:p(.*)*', component: { template: '<div />' } },
    ],
  })
}

describe('任务与项目里的 AI Run 入口已隐藏', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore(useDataStore())
  })

  it('开关默认关闭，「AI 进行中」不再作为可选状态，已处于该状态的任务仍能看到当前值', () => {
    expect(AI_RUN_ENABLED).toBe(false)
    expect(selectableTaskStatuses('待办')).not.toContain('AI 进行中')
    expect(selectableTaskStatuses('AI 进行中')).toContain('AI 进行中')
  })

  it('任务弹框不展示 AI run、停止 AI 与执行档案，也不轮询运行记录', async () => {
    const { fetchTaskAiRuns } = await import('@/api/ai')
    const data = useDataStore()
    const taskId = data.tasks[0]!.id
    const subId = data.addSub(taskId, '子任务')!
    useUiStore().openTask(taskId)
    const router = makeRouter()
    const wrapper = mount(TaskDrawer, { global: { plugins: [router] }, attachTo: document.body })
    await flushPromises()

    expect(wrapper.find('.ai-run-btn').exists()).toBe(false)
    expect(wrapper.find('.pause-ai-btn').exists()).toBe(false)
    expect(wrapper.find('.ai-archive').exists()).toBe(false)
    expect(fetchTaskAiRuns).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('AI run')

    useUiStore().openTask(taskId, subId)
    await flushPromises()
    expect(wrapper.find('.props').text()).not.toContain('交给 AI 跑')
  })

  it('项目批量选择后只保留复制，不再出现「给 AI」', async () => {
    const router = makeRouter()
    await router.push('/projects/p1?view=list')
    await router.isReady()
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()
    await wrapper.get('.batch-selection-toggle').trigger('click')
    await wrapper.get('.task-row .select-dot').trigger('click')

    expect(wrapper.get('.selection-count').text()).toBe('已选 1 项')
    expect(wrapper.find('.batch-ai-actions').exists()).toBe(false)
  })
})

describe('项目表格按分组查看与修改', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore(useDataStore())
  })

  it('按分组分段展示，行内改分组即时生效，分组头可折叠', async () => {
    const data = useDataStore()
    await data.setTaskGroups('p1', [{ id: 'g1', name: '证件' }, { id: 'g2', name: '装修' }])
    const router = makeRouter()
    await router.push('/projects/p1?view=list')
    await router.isReady()
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('.table-group-row').exists()).toBe(false)
    await wrapper.findAll('.table-seg button').find((b) => b.text() === '按分组')!.trigger('click')

    const heads = wrapper.findAll('.table-group-row')
    expect(heads.map((row) => row.find('.group-name').text())).toEqual(['证件', '装修'])

    const openTask = data.tasksByProject('p1').find((task) => task.status !== '已完成')!
    const row = wrapper.findAll('.task-row').find((node) => node.text().includes(openTask.title))!
    await row.get('.group-select').setValue('g2')
    expect(data.taskById(openTask.id)!.groupId).toBe('g2')

    await wrapper.findAll('.table-group-row')[1]!.get('.group-collapse').trigger('click')
    expect(wrapper.findAll('.task-row').some((node) => node.text().includes(openTask.title))).toBe(false)
  })
})
