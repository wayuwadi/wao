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
import { useUiStore } from '@/stores/ui'

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

  it('象限入口预设优先级，隔离普通草稿并恢复自己的未提交内容', async () => {
    const original = JSON.stringify({ title: '普通入口草稿', pr: 'P2' })
    localStorage.setItem('wao-new-task-draft-v1', original)
    const ui = useUiStore()
    ui.openModal('task')
    ui.modalPriority = 'P1'
    let wrapper = mount(NewTaskModal, { global: { stubs: { MarkdownEditor: true } } })
    expect(wrapper.get('.title-input').element).toHaveProperty('value', '')
    await wrapper.get('.title-input').setValue('象限中的草稿')
    wrapper.unmount()
    expect(localStorage.getItem('wao-new-task-draft-v1')).toBe(original)
    wrapper = mount(NewTaskModal, { global: { stubs: { MarkdownEditor: true } } })
    expect(wrapper.get('.title-input').element).toHaveProperty('value', '象限中的草稿')
    putTask.mockImplementation(savedTask)
    await wrapper.get('.foot .btn-primary').trigger('click')
    await flushPromises()
    expect(data.tasks.find((task) => task.title === '象限中的草稿')?.pr).toBe('P1')
    expect(localStorage.getItem('wao-new-task-draft-v1')).toBe(original)
    expect(localStorage.getItem('wao-new-task-quadrant-P1-draft-v1')).toBeNull()
    wrapper.unmount()
  })

  it('文档待办使用来源项目和完整文字，不覆盖普通任务草稿；失败后同一任务重试', async () => {
    const original = JSON.stringify({ title: '普通任务未提交草稿', project: 'p1' })
    localStorage.setItem('wao-new-task-draft-v1', original)
    const ui = useUiStore()
    const target = data.projects.find((p) => p.id !== 'p1' && p.status !== '已完成')!
    ui.openModal('task', target.id)
    ui.modalTaskSeed = { draftKey: 'document-selection-test', title: '文档里的待办', desc: '完整选区\n\n来源文档：/kb/k3?doc=d7', sourceTitle: '来源文档', sourceUrl: '/kb/k3?doc=d7', sourceDocId: 'd7' }
    const wrapper = mount(NewTaskModal, { global: { stubs: { MarkdownEditor: true } } })
    expect(wrapper.get('.title-input').element).toHaveProperty('value', '文档里的待办')
    expect(wrapper.findAll('.pill.on').some((pill) => pill.text().includes(target.name))).toBe(true)
    await wrapper.get('.title-input').setValue('文档待办修改后标题')
    putTask.mockRejectedValueOnce(new Error('网络中断'))
    await wrapper.get('.foot .btn-primary').trigger('click')
    await flushPromises()
    const firstId = putTask.mock.calls[0]![0]
    expect(putTask.mock.calls[0]![1]).toMatchObject({ title: '文档待办修改后标题', project: target.id, docs: ['d7'], desc: '完整选区\n\n来源文档：/kb/k3?doc=d7' })
    expect(wrapper.get('[role="alert"]').text()).toContain('未保存')
    expect(localStorage.getItem('wao-new-task-draft-v1')).toBe(original)
    expect(localStorage.getItem('document-selection-test')).toContain('文档待办修改后标题')
    putTask.mockImplementationOnce(savedTask)
    await wrapper.get('.foot .btn-primary').trigger('click')
    await flushPromises()
    expect(putTask.mock.calls[1]![0]).toBe(firstId)
    expect(localStorage.getItem('document-selection-test')).toBeNull()
    expect(localStorage.getItem('wao-new-task-draft-v1')).toBe(original)
    wrapper.unmount()
  })

  it('无关联项目的文档要求明确选择，不能默认投到其他项目', async () => {
    const ui = useUiStore()
    ui.openModal('task')
    ui.modalTaskSeed = { draftKey: 'unbound-document-selection', title: '无项目待办', desc: '选区正文', sourceTitle: '个人文档', sourceUrl: '/kb/k3?doc=d7' }
    const wrapper = mount(NewTaskModal, { global: { stubs: { MarkdownEditor: true } } })
    expect(wrapper.text()).toContain('请选择要存入的项目')
    await wrapper.get('.foot .btn-primary').trigger('click')
    expect(putTask).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('浏览器拒绝草稿写入时保留表单并允许重试，不假报草稿已保存', async () => {
    const ui = useUiStore()
    ui.openModal('task', 'p1')
    ui.modalTaskSeed = { draftKey: 'blocked-document-draft', title: '草稿保存失败仍保留', desc: '完整原文', sourceTitle: '文档', sourceUrl: '/kb/k3?doc=d7' }
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('QuotaExceededError') })
    const wrapper = mount(NewTaskModal, { global: { stubs: { MarkdownEditor: true } } })
    expect(wrapper.get('[role="alert"]').text()).toContain('草稿未能写入本机')
    expect(wrapper.get('.title-input').element).toHaveProperty('value', '草稿保存失败仍保留')
    storage.mockRestore()
    await wrapper.get('[role="alert"] button').trigger('click')
    expect(localStorage.getItem('blocked-document-draft')).toContain('完整原文')
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
