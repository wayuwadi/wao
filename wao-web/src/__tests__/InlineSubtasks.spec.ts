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
