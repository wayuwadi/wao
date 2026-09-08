import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { exportTasksMarkdown } from '@/utils/taskExport'
import { copyToClipboard } from '@/utils/clipboard'
import TaskBatchCopy from '@/components/task/TaskBatchCopy.vue'
import TodoView from '@/views/TodoView.vue'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { seedDataStore } from './helpers/seedDataStore'
import type { Task } from '@/types'

vi.mock('@/utils/clipboard', () => ({ copyToClipboard: vi.fn().mockResolvedValue(true) }))

describe('批量复制任务全文', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore()
    vi.mocked(copyToClipboard).mockReset().mockResolvedValue(true)
  })

  it('保留全文、HTML、子任务详情、图片与关联引用，不摘要或修改任务', () => {
    const task: Task = {
      ...useDataStore().tasks[0]!, title: '导出完整任务', desc: '<p>原始描述</p>\n```js\nconst x = 1\n```',
      images: [{ name: '主图', url: '/api/images/main.png' }],
      steps: [{ text: '步骤一', done: false, desc: '步骤详情' }],
      subs: [{ id: 's1', text: '子任务', done: true, desc: '第一行\n第二行', images: [{ name: '子图', url: '/sub.png' }] }],
      comments: [{ who: 'wg', time: '2026-09-04', text: '评论全文' }],
      custom: [{ name: '验收', value: '必须刷新后仍在' }],
      files: [{ id: 'f1', name: '附件', size: '1 KB', icon: '' }],
      docs: ['d1'], linkTasks: [{ id: 't-other', rel: '依赖' }], hostedPages: ['h1'],
      pdcaCycles: [{ id: 'cycle1', plan: '真实计划', check: '真实结果', act: '真实改进', startedAt: '2026-09-04' }],
    }
    const before = JSON.stringify(task)
    const out = exportTasksMarkdown([task, task, { ...task, id: 'second', status: '已完成' }], {
      origin: 'https://wao.example', projectName: () => '项目名', memberName: () => '成员名',
    })
    for (const text of ['任务清单（2 项）', task.desc, '  第一行\n  第二行', '- [x] 子任务',
      'https://wao.example/sub.png', 'https://wao.example/api/images/main.png', '评论全文', '必须刷新后仍在',
      '/f/f1', '/api/share/docs/d1.md', 't-other', 'h1', '真实计划', '真实结果', '真实改进', '项目名', '成员名']) {
      expect(out).toContain(text)
    }
    expect(JSON.stringify(task)).toBe(before)
  })

  it('剪贴板失败保留预览，后台同步不替换快照，重试与重新生成可用', async () => {
    const task = useDataStore().tasks[0]!
    vi.mocked(copyToClipboard).mockResolvedValueOnce(false)
    const wrapper = mount(TaskBatchCopy, { props: { tasks: [task] } })
    await wrapper.get('.copy-tasks').trigger('click')
    await flushPromises()
    const snapshot = wrapper.get('textarea').element.value
    expect(wrapper.get('[role="alert"]').text()).toContain('手动复制')
    await wrapper.setProps({ tasks: [{ ...task, desc: '后台新版' }] })
    expect(wrapper.get('textarea').element.value).toBe(snapshot)
    await wrapper.get('footer button').trigger('click')
    await flushPromises()
    expect(copyToClipboard).toHaveBeenLastCalledWith(snapshot)
    expect(wrapper.get('[role="status"]').text()).toContain('已复制')
    await wrapper.get('.copy-tasks').trigger('click')
    expect(wrapper.get('textarea').element.value).toContain('后台新版')
    wrapper.unmount()
  })

  it('未保存与空选择不允许生成，保存失败不会被标记为成功', async () => {
    const data = useDataStore()
    const state = vi.spyOn(data, 'taskSaveState').mockReturnValue('error')
    const wrapper = mount(TaskBatchCopy, { props: { tasks: [data.tasks[0]!] } })
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('尚未保存')
    state.mockReturnValue('saved')
    await wrapper.setProps({ tasks: [] })
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(copyToClipboard).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it.each(['list', 'board', 'group'])('项目 %s 视图选择、全选和清空均可复制，筛选隐藏项不继续选中', async (view) => {
    const data = useDataStore()
    data.tasks = data.tasks.filter((task) => task.project === 'p1').map((task) => ({ ...task, status: '待办' }))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:id', component: ProjectDetailView }] })
    await router.push(`/projects/p1?view=${view}`)
    const wrapper = mount(ProjectDetailView, { global: { plugins: [router], stubs: { ProjectMemoryPanel: true, ProjectAssetsPanel: true } } })
    await flushPromises()
    await wrapper.get('.batch-selection-toggle').trigger('click')
    await wrapper.get('.select-dot').trigger('click')
    await wrapper.get('.copy-tasks').trigger('click')
    expect(copyToClipboard).toHaveBeenCalledOnce()
    await wrapper.get('.copy-selection-actions button').trigger('click')
    expect(wrapper.get('.copy-tasks').text()).toContain(`· ${data.tasks.length}`)
    await router.push(`/projects/p1?view=${view}&stage=verified`)
    await flushPromises()
    expect(wrapper.get('.copy-tasks').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('TODO 可跨项目复制，选择不等于完成，过滤掉隐藏任务', async () => {
    const data = useDataStore()
    const me = useSessionStore().currentUser.id
    data.tasks = data.tasks.slice(0, 2).map((task, index) => ({ ...task, owner: me, parts: [], project: `p${index + 1}`, status: '待办' }))
    const wrapper = mount(TodoView)
    await wrapper.get('.copy-selection-actions button').trigger('click')
    await wrapper.get('.copy-tasks').trigger('click')
    expect(vi.mocked(copyToClipboard).mock.calls[0]![0]).toContain('任务清单（2 项）')
    expect(data.tasks.every((task) => task.status === '待办')).toBe(true)
    await wrapper.findAll('.mt-toolbar > button').find((button) => button.text() === '我参与')!.trigger('click')
    expect(wrapper.get('.copy-tasks').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
})
