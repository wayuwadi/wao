import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import InlineSubtasks from '@/components/task/InlineSubtasks.vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { seedDataStore } from './helpers/seedDataStore'
import { apiPutTask } from '@/api/board'
vi.mock('@/api/board', async (original) => ({ ...(await original<object>()), apiPutTask: vi.fn() }))
describe('列表内子任务', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore(useDataStore())
    useDataStore().apiLive = true
    vi.mocked(apiPutTask).mockReset()
    vi.mocked(apiPutTask).mockImplementation(async (_id, task) => task as never)
  })
  it.each([true, false])('冲突草稿提供处理入口而不是无效重试（table=%s）', async (table) => {
    const data = useDataStore()
    const task = data.tasks[0]!
    data.taskConflicts[task.id] = true
    vi.spyOn(data, 'taskSaveState').mockReturnValue('error')
    const wrapper = mount(InlineSubtasks, { props: { taskId: task.id, table } })
    expect(wrapper.text()).not.toContain('重试保存')
    const action = wrapper.findAll('button').find(button => button.text() === '查看并处理冲突')!
    await action.trigger('click')
    expect(useUiStore().openTaskId).toBe(task.id)
    expect(apiPutTask).not.toHaveBeenCalled()
    expect(data.taskConflicts[task.id]).toBe(true)
    wrapper.unmount()
    vi.restoreAllMocks()
  })
  it('直接新增、编号与跳转，保存失败可重试而不重复添加', async () => {
    const data = useDataStore()
    const task = data.tasks[0]!
    task.subs = [{ id: 'sub-old', text: '原子任务', done: true, number: 0 }]
    const wrapper = mount(InlineSubtasks, { props: { taskId: task.id } })
    vi.mocked(apiPutTask).mockRejectedValueOnce(new Error('离线'))
    await wrapper.get('form input').setValue('新增子任务')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(task.subs.map((sub) => sub.text)).toEqual(['新增子任务', '原子任务'])
    expect(wrapper.findAll('.number').map((item) => item.text())).toEqual(['…', '0'])
    expect(wrapper.text()).toContain('保存失败')
    await wrapper.get('.error button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已保存')
    expect(task.subs).toHaveLength(2)
    await wrapper.get('.inline-sub button').trigger('click')
    expect(useUiStore().openTaskId).toBe(task.id)
    expect(useUiStore().openTaskSubId).toBe(task.subs[0]!.id)
    wrapper.unmount()
  })
  it('表格使用固定编号，重排和完成不会改变身份', async () => {
    const data = useDataStore()
    const task = data.tasks[0]!
    task.subs = [
      { id: 's1', text: '第一项', done: false, number: 0 },
      { id: 's2', text: '第二项', done: false, number: 8 },
    ]
    const wrapper = mount(InlineSubtasks, { props: { taskId: task.id, table: true } })
    expect(wrapper.element.tagName).toBe('TBODY')
    expect(wrapper.findAll('.sub-number').map((node) => node.text())).toEqual(['0', '8'])
    task.subs.reverse()
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('.sub-number').map((node) => node.text())).toEqual(['8', '0'])
    await wrapper.get('.sub-state').trigger('click')
    const done = [...document.querySelectorAll<HTMLButtonElement>('.cell-menu button')].find((node) => node.textContent?.includes('已完成'))!
    done.click()
    await wrapper.vm.$nextTick()
    expect(task.subs[0]!.done).toBe(true)
    expect(task.subs[0]!.number).toBe(8)
    await wrapper.setProps({ hideDone: true })
    expect(wrapper.findAll('.sub-number').map((node) => node.text())).toEqual(['0'])
    wrapper.unmount()
  })
  it.each([true, false])('待验证可保存并留在未完成视图，用户可完成后重新打开（table=%s）', async (table) => {
    const data = useDataStore()
    const task = data.tasks[0]!
    task.subs = [{ id: 'verify-sub', text: '等待用户验收', done: false }]
    const wrapper = mount(InlineSubtasks, { props: { taskId: task.id, table, hideDone: true }, attachTo: document.body })
    await wrapper.get('.sub-state').trigger('click')
    const choice = [...document.querySelectorAll<HTMLButtonElement>('[role="menuitemradio"]')].find(node => node.textContent?.trim() === '待验证')!
    expect(choice).toBeTruthy()
    choice.click()
    await flushPromises()
    await data.flushTask(task.id)
    expect(task.subs[0]).toMatchObject({ done: false, status: '待验证' })
    expect(apiPutTask).toHaveBeenLastCalledWith(task.id, expect.objectContaining({ subs: [expect.objectContaining({ id: 'verify-sub', done: false, status: '待验证' })] }))
    expect(wrapper.get('.sub-state').text()).toBe('待验证')
    wrapper.unmount()
    const restored = mount(InlineSubtasks, { props: { taskId: task.id, table, hideDone: true } })
    expect(restored.get('.sub-state').text()).toBe('待验证')
    data.setSubState(task.id, 'verify-sub', '已完成')
    await flushPromises()
    if (!table) await restored.get('.done-switch').trigger('click')
    expect(restored.find('.sub-state').exists()).toBe(false)
    data.setSubState(task.id, 'verify-sub', '未开始')
    await flushPromises()
    expect(restored.get('.sub-state').text()).toBe('未开始')
    expect(task.subs[0]).toMatchObject({ done: false, status: null })
    restored.unmount()
  })

  it('新子任务显示 P2，调整只保存子任务自己的优先级', async () => {
    const data = useDataStore()
    const task = data.tasks[0]!
    task.pr = 'P0'
    task.subs = []
    const id = data.addSub(task.id, '默认普通优先级')!
    const wrapper = mount(InlineSubtasks, { props: { taskId: task.id, table: true } })
    expect(wrapper.get('.sub-priority').text()).toBe('P2')
    await wrapper.get('.sub-priority').trigger('click')
    const choice = [...document.querySelectorAll<HTMLButtonElement>('.cell-menu button')].find(node => node.textContent?.trim() === 'P1')!
    choice.click()
    await wrapper.vm.$nextTick()
    expect(data.subById(task.id, id)?.pr).toBe('P1')
    expect(task.pr).toBe('P0')
    await data.flushTask(task.id)
    expect(apiPutTask).toHaveBeenLastCalledWith(task.id, expect.objectContaining({ subs: [expect.objectContaining({ id, pr: 'P1' })] }))
    wrapper.unmount()
  })

  it('未提交的文字在重挂载时恢复', async () => {
    const id = useDataStore().tasks[0]!.id
    const first = mount(InlineSubtasks, { props: { taskId: id } })
    await first.get('form input').setValue('保留输入')
    first.unmount()
    const second = mount(InlineSubtasks, { props: { taskId: id } })
    expect(second.get<HTMLInputElement>('form input').element.value).toBe('保留输入')
    second.unmount()
  })
})
