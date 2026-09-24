import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { ref } from 'vue'
import TaskQuadrantView from '@/components/task/TaskQuadrantView.vue'
import TodoView from '@/views/TodoView.vue'
import { TASK_QUADRANTS, readTodoTaskView } from '@/utils/taskQuadrants'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { seedDataStore } from './helpers/seedDataStore'

vi.mock('@/api/board', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/api/board')>(),
  apiPutTask: vi.fn(async (_id: string, task: import('@/types').Task) => JSON.parse(JSON.stringify(task))),
}))

describe('TODO 四象限', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    const data = seedDataStore()
    const source = data.tasks[0]!
    data.tasks = TASK_QUADRANTS.map((quadrant, index) => ({
      ...source, id: `q${index}`, title: `任务${index}`, pr: quadrant.priority,
      owner: useSessionStore().currentUser.id, parts: [], status: '待办',
    }))
  })

  function mountQuadrants() {
    return mount(TaskQuadrantView, { props: { tasks: useDataStore().tasks, selectedIds: [], showDone: false } })
  }

  it('任务右键与可见更多入口打开命中任务的操作', async () => {
    const wrapper = mountQuadrants()
    await wrapper.get('article').trigger('contextmenu', { clientX: 140, clientY: 120 })
    await flushPromises()
    expect(document.body.querySelector('[role="menu"]')?.getAttribute('aria-label')).toBe('任务：任务0的操作')
    await wrapper.get('article .quadrant-more').trigger('click')
    await flushPromises()
    expect(document.body.querySelector('[role="menu"]')?.textContent).toContain('复制任务编号')
    wrapper.unmount()
  })

  it('P0–P3 分别且唯一归属四个象限，截止日期不改变映射', () => {
    const data = useDataStore()
    data.tasks[0]!.due = '2099-01-01'
    data.tasks[3]!.due = '2000-01-01'
    const wrapper = mountQuadrants()
    expect(wrapper.findAll('.quadrant')).toHaveLength(4)
    wrapper.findAll('.quadrant').forEach((section, index) => {
      expect(section.get('h2').text()).toBe(TASK_QUADRANTS[index]!.label)
      expect(section.findAll('article')).toHaveLength(1)
      expect(section.get('.quadrant-title').text()).toBe(`任务${index}`)
    })
    wrapper.unmount()
  })

  it('选择象限只改优先级，保存成功后位置与详情入口一致', async () => {
    const data = useDataStore()
    const before = JSON.stringify({ ...data.tasks[0], pr: null, activity: [] })
    const wrapper = mountQuadrants()
    await wrapper.get('select[aria-label="调整象限 任务0"]').setValue('P1')
    await flushPromises()
    expect(data.tasks[0]!.pr).toBe('P1')
    expect(JSON.stringify({ ...data.tasks[0], pr: null, activity: [] })).toBe(before)
    expect(wrapper.findAll('.quadrant')[1]!.findAll('article')).toHaveLength(2)
    expect(data.taskSaveState('q0')).toBe('saved')
    await wrapper.findAll('.quadrant-title').find((button) => button.text() === '任务0')!.trigger('click')
    expect(useUiStore().openTaskId).toBe('q0')
    wrapper.unmount()
  })

  it('保存中禁止重复修改，失败保留本地值并支持重试', async () => {
    const data = useDataStore()
    const state = ref<'idle' | 'saving' | 'error' | 'saved'>('idle')
    vi.spyOn(data, 'taskSaveState').mockImplementation(() => state.value)
    let finish: (() => void) | undefined
    const flush = vi.spyOn(data, 'flushTask').mockImplementationOnce(() => {
      state.value = 'saving'
      return new Promise<void>((resolve) => { finish = resolve })
    })
    const wrapper = mountQuadrants()
    await wrapper.get('select[aria-label="调整象限 任务0"]').setValue('P3')
    expect(wrapper.get('select[aria-label="调整象限 任务0"]').attributes('disabled')).toBeDefined()
    state.value = 'error'
    finish?.()
    await flushPromises()
    expect(data.tasks[0]!.pr).toBe('P3')
    expect(wrapper.text()).toContain('保存尚未确认，内容已留在本机')
    flush.mockImplementationOnce(async () => { state.value = 'saved' })
    const retryButtons = wrapper.findAll('.quadrant-save button')
    await retryButtons[retryButtons.length - 1]!.trigger('click')
    await flushPromises()
    expect(flush).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).not.toContain('重试保存')
    wrapper.unmount()
  })

  it('拖动调整优先级，取消拖动不留下一次误操作', async () => {
    const wrapper = mountQuadrants()
    await wrapper.get('article').trigger('dragstart')
    await wrapper.findAll('.quadrant')[2]!.trigger('drop')
    await flushPromises()
    expect(useDataStore().tasks[0]!.pr).toBe('P2')
    await wrapper.get('article').trigger('dragstart')
    await wrapper.get('article').trigger('dragend')
    const snapshot = useDataStore().tasks.map((task) => task.pr)
    await wrapper.findAll('.quadrant')[3]!.trigger('drop')
    expect(useDataStore().tasks.map((task) => task.pr)).toEqual(snapshot)
    wrapper.unmount()
  })

  it('任务标题自身可启动拖动，批量选择期间禁止拖动', async () => {
    const wrapper = mountQuadrants()
    const title = wrapper.get('.quadrant-title')
    expect(title.attributes('draggable')).toBe('true')
    await title.trigger('dragstart')
    await wrapper.findAll('.quadrant')[1]!.trigger('drop')
    await flushPromises()
    expect(useDataStore().tasks[0]!.pr).toBe('P1')
    await wrapper.setProps({ selectionMode: true })
    expect(wrapper.findAll('.quadrant-title').every((button) => button.attributes('draggable') === 'false')).toBe(true)
    wrapper.unmount()
  })

  it('完成默认收起，展开后可选择，收起和筛选清除隐藏选择', async () => {
    const data = useDataStore()
    data.tasks[0]!.status = '已完成'
    localStorage.setItem('wao-todo-view-v1', 'quadrant')
    const wrapper = mount(TodoView)
    expect(wrapper.findAll('article')).toHaveLength(3)
    await wrapper.get('.quadrant-toolbar button').trigger('click')
    expect(wrapper.findAll('article')).toHaveLength(4)
    await wrapper.findAll('.mt-toolbar > button').find((button) => button.text() === '批量选择')!.trigger('click')
    await wrapper.get('.copy-selection-actions button').trigger('click')
    expect(wrapper.get('.copy-tasks').text()).toContain('· 4')
    await wrapper.get('.quadrant-toolbar button').trigger('click')
    expect(wrapper.get('.copy-tasks').text()).toContain('· 3')
    await wrapper.findAll('.mt-toolbar > button').find((button) => button.text() === '我参与')!.trigger('click')
    expect(wrapper.findAll('.quadrant-empty')).toHaveLength(4)
    expect(wrapper.get('.copy-tasks').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })

  it('记住四象限偏好，损坏或不可用的存储安全回退', async () => {
    expect(readTodoTaskView()).toBe('list')
    const wrapper = mount(TodoView)
    await wrapper.findAll('.seg-btn').find((button) => button.text().includes('四象限'))!.trigger('click')
    expect(localStorage.getItem('wao-todo-view-v1')).toBe('quadrant')
    expect(readTodoTaskView()).toBe('quadrant')
    wrapper.unmount()
    localStorage.setItem('wao-todo-view-v1', 'invalid')
    expect(readTodoTaskView()).toBe('list')
    const broken = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('禁止存储') })
    expect(readTodoTaskView()).toBe('list')
    broken.mockRestore()
  })

  it('完成勾选不会批量选择，缓冲后归档且可重新打开', async () => {
    vi.useFakeTimers()
    const wrapper = mountQuadrants()
    await wrapper.get('[aria-label="完成任务 任务0"]').trigger('click')
    await vi.advanceTimersByTimeAsync(50)
    expect(useDataStore().tasks[0]!.status).toBe('已完成')
    expect(wrapper.emitted('select')).toBeUndefined()
    expect(wrapper.find('[aria-label="重新打开 任务0"]').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(3000)
    expect(wrapper.find('[aria-label="重新打开 任务0"]').exists()).toBe(false)
    await wrapper.setProps({ showDone: true })
    await wrapper.get('[aria-label="重新打开 任务0"]').trigger('click')
    await vi.advanceTimersByTimeAsync(50)
    expect(useDataStore().tasks[0]!.status).toBe('待办')
    wrapper.unmount()
    vi.useRealTimers()
  })

  it('完成保存失败时不隐藏任务，重试成功后才归档', async () => {
    vi.useFakeTimers()
    const data = useDataStore()
    const state = ref<'idle' | 'error' | 'saved'>('idle')
    vi.spyOn(data, 'taskSaveState').mockImplementation((id) => id === 'q0' ? state.value : 'idle')
    const flush = vi.spyOn(data, 'flushTask').mockImplementationOnce(async () => { state.value = 'error' })
    const wrapper = mountQuadrants()
    await wrapper.get('[aria-label="完成任务 任务0"]').trigger('click')
    await vi.advanceTimersByTimeAsync(3100)
    expect(wrapper.find('[aria-label="重新打开 任务0"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('保存尚未确认，内容已留在本机')
    flush.mockImplementationOnce(async () => { state.value = 'saved' })
    await wrapper.get('.quadrant-save button').trigger('click')
    await vi.advanceTimersByTimeAsync(50)
    expect(wrapper.find('[aria-label="重新打开 任务0"]').exists()).toBe(false)
    wrapper.unmount()
    vi.useRealTimers()
  })

  it('放大与还原保持任务不变，每格新建带入对应优先级', async () => {
    const wrapper = mountQuadrants()
    const before = JSON.stringify(useDataStore().tasks)
    await wrapper.get('[aria-label="放大重要不紧急"]').trigger('click')
    expect(wrapper.findAll('.quadrant').filter((section) => section.isVisible())).toHaveLength(1)
    await wrapper.get('[aria-label="还原重要不紧急"]').trigger('click')
    expect(wrapper.get('.quadrants').classes()).not.toContain('expanded')
    expect(wrapper.findAll('.quadrant').filter((section) => (section.element as HTMLElement).style.display !== 'none')).toHaveLength(4)
    for (const quadrant of TASK_QUADRANTS) {
      await wrapper.get(`[aria-label="在${quadrant.label}新建任务"]`).trigger('click')
      expect(useUiStore().modal).toBe('task')
      expect(useUiStore().modalPriority).toBe(quadrant.priority)
    }
    useUiStore().openModal('task')
    expect(useUiStore().modalPriority).toBeNull()
    expect(JSON.stringify(useDataStore().tasks)).toBe(before)
    wrapper.unmount()
  })
})
