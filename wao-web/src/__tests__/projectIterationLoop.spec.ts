import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { ref } from 'vue'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import type { AiRun } from '@/types'

const { createAiRuns, fetchTaskAiRuns } = vi.hoisted(() => ({
  createAiRuns: vi.fn<(taskIds: string[]) => Promise<AiRun[]>>(),
  fetchTaskAiRuns: vi.fn<(taskId: string) => Promise<AiRun[]>>(),
}))
vi.mock('@/api/ai', () => ({ createAiRuns, fetchTaskAiRuns }))
vi.mock('@/api/board', () => ({
  apiEnsureProjectBrief: vi.fn<() => Promise<unknown>>(),
  apiStartProject: vi.fn<() => Promise<unknown>>(),
}))

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/projects/:id', component: { template: '<div/>' } }, { path: '/:p(.*)*', component: { template: '<div/>' } }],
})

async function mountProject() {
  await router.push('/projects/p1')
  await router.isReady()
  const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

function button(wrapper: Awaited<ReturnType<typeof mountProject>>, text: string) {
  const target = wrapper.findAll('button').find((item) => item.text().includes(text))
  expect(target, `没有找到按钮：${text}`).toBeTruthy()
  return target!
}

describe('项目多轮验收闭环', () => {
  let data: ReturnType<typeof useDataStore>

  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    seedDataStore(data)
    fetchTaskAiRuns.mockResolvedValue([])
    createAiRuns.mockResolvedValue([])
  })

  it('本轮满意写透失败时保留重试入口，重试成功也不会完成项目', async () => {
    const latest = [...data.tasksByProject('p1')].sort((a, b) =>
      (b.createdAt ?? b.id).localeCompare(a.createdAt ?? a.id))[0]!
    latest.status = '待验证'
    const saveFailed = ref(false)
    vi.spyOn(data, 'taskSaveState').mockImplementation(() => saveFailed.value ? 'error' : 'saved')
    vi.spyOn(data, 'flushTask').mockImplementation(async () => { saveFailed.value = !saveFailed.value })
    const wrapper = await mountProject()

    await button(wrapper, '本轮满意').trigger('click')
    await flushPromises()

    expect(latest.status).toBe('已完成')
    expect(data.projectById('p1')?.status).toBe('进行中')
    expect(wrapper.text()).toContain('重试本轮验收')

    await button(wrapper, '重试本轮验收').trigger('click')
    await flushPromises()

    expect(wrapper.text()).not.toContain('重试本轮验收')
    expect(data.projectById('p1')?.status).toBe('进行中')
    wrapper.unmount()
  })

  it('开始下一轮会新建独立任务和 AI Run，并保持项目开放', async () => {
    const run = { id: 'ar-next', status: 'queued', createdAt: '2026-08-25T08:00:00+08:00' } as AiRun
    createAiRuns.mockResolvedValue([run])
    const before = data.tasksByProject('p1').length
    const wrapper = await mountProject()

    await button(wrapper, '开始下一轮').trigger('click')
    await wrapper.get('.next-round-form input').setValue('补齐失败恢复体验')
    await wrapper.get('.next-round-form textarea').setValue('保存失败后可以原地重试')
    await button(wrapper, '创建任务并 Run').trigger('click')
    await flushPromises()

    const tasks = data.tasksByProject('p1')
    expect(tasks).toHaveLength(before + 1)
    expect(tasks[0]?.title).toContain('补齐失败恢复体验')
    expect(tasks[0]?.desc).toContain('保存失败后可以原地重试')
    expect(createAiRuns).toHaveBeenCalledWith([tasks[0]!.id])
    expect(data.projectById('p1')?.status).toBe('进行中')
    wrapper.unmount()
  })
})
