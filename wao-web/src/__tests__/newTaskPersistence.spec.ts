import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import NewTaskModal from '@/components/overlays/NewTaskModal.vue'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { apiPutTask } from '@/api/board'
import { useToastHost } from '@/composables/toast'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import type { Task } from '@/types'

vi.mock('@/api/board', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/api/board')>(),
  apiPutTask: vi.fn<typeof apiPutTask>(),
}))

vi.mock('@/api/ai', () => ({
  createAiRuns: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
  fetchTaskAiRuns: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
}))

const putTask = vi.mocked(apiPutTask)

function savedTask(id: string, task: Task): Promise<Task> {
  return Promise.resolve({ ...task, id })
}

describe('新建任务的落库反馈', () => {
  let data: ReturnType<typeof useDataStore>

  beforeEach(() => {
    localStorage.clear()
    useToastHost().items.splice(0)
    setActivePinia(createPinia())
    data = useDataStore()
    seedDataStore(data)
    data.apiLive = true
    putTask.mockReset()
  })

  it('服务端确认前保持提交中，成功后才提示已创建', async () => {
    let confirm!: (task: Task) => void
    putTask.mockImplementationOnce((_id, task) => new Promise((resolve) => { confirm = resolve; void task }))
    const wrapper = mount(NewTaskModal, { global: { stubs: { MarkdownEditor: true } } })
    const before = data.tasks.length

    await wrapper.find('.title-input').setValue('等待服务端确认的任务')
    void wrapper.find('.foot .btn-primary').trigger('click')
    await wrapper.vm.$nextTick()

    expect(data.tasks).toHaveLength(before + 1)
    expect(wrapper.find('.foot .btn-primary').text()).toContain('正在创建')
    expect(wrapper.find('.foot .btn-primary').attributes()).toHaveProperty('disabled')
    expect(wrapper.find('.close').attributes()).toHaveProperty('disabled')
    expect(useToastHost().items.some((item) => item.text.includes('已创建'))).toBe(false)

    confirm({ ...data.tasks[0]! })
    await flushPromises()

    expect(useToastHost().items.some((item) => item.text.includes('已创建'))).toBe(true)
    wrapper.unmount()
  })

  it('失败时保留表单和草稿，重试同一任务成功且不重复插入', async () => {
    putTask.mockRejectedValueOnce(new Error('网络中断'))
    const wrapper = mount(NewTaskModal, { global: { stubs: { MarkdownEditor: true } } })
    const before = data.tasks.length

    await wrapper.find('.title-input').setValue('失败后仍可重试的任务')
    await wrapper.find('.foot .btn-primary').trigger('click')
    await flushPromises()

    const alert = wrapper.get('[role="alert"]')
    expect(alert.text()).toContain('未保存到服务器')
    // 失败状态跟重试按钮都在手机贴底操作区里，不会滚到键盘后面。
    expect(alert.element.parentElement?.classList.contains('foot')).toBe(true)
    expect(wrapper.find('.title-input').element).toHaveProperty('value', '失败后仍可重试的任务')
    expect(wrapper.find('.foot .btn-primary').text()).toContain('重试创建')
    expect(localStorage.getItem('wao-new-task-draft-v1')).toContain('失败后仍可重试的任务')
    expect(data.tasks).toHaveLength(before + 1)

    putTask.mockImplementationOnce(savedTask)
    await wrapper.find('.foot .btn-primary').trigger('click')
    await flushPromises()

    expect(data.tasks).toHaveLength(before + 1)
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(useToastHost().items.some((item) => item.text.includes('已创建'))).toBe(true)
    wrapper.unmount()
  })

  it('分组快速新增失败时不清空输入，原地重试后仍只有一条任务', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/projects/:id', component: { template: '<div />' } }],
    })
    await router.push('/projects/p1?view=group')
    await router.isReady()
    putTask.mockRejectedValueOnce(new Error('服务不可用'))
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router] } })
    const before = data.tasksByProject('p1').length

    await wrapper.find('.group-head .group-op').trigger('click')
    await wrapper.find('.group-add-row input').setValue('行内重试任务')
    await wrapper.find('.group-add-row input').trigger('keydown.enter')
    await flushPromises()

    expect(wrapper.get('.group-add-error').text()).toContain('输入仍保留')
    expect(wrapper.find('.group-add-row input').element).toHaveProperty('value', '行内重试任务')
    expect(wrapper.find('.group-add-done').text()).toBe('重试')
    expect(data.tasksByProject('p1')).toHaveLength(before + 1)

    putTask.mockImplementationOnce(savedTask)
    await wrapper.find('.group-add-done').trigger('click')
    await flushPromises()

    expect(wrapper.find('.group-add-error').exists()).toBe(false)
    expect(wrapper.find('.group-add-row input').element).toHaveProperty('value', '')
    expect(data.tasksByProject('p1')).toHaveLength(before + 1)
    wrapper.unmount()
  })
})
