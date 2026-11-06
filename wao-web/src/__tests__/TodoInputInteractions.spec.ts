import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { DOMWrapper, enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TodoListHeader from '@/components/todo/TodoListHeader.vue'
import TaskListDialog from '@/components/todo/TaskListDialog.vue'
import { defaultTodoView } from '@/utils/todoViewSettings'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { seedDataStore } from './helpers/seedDataStore'

enableAutoUnmount(afterEach)
beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); seedDataStore() })
afterEach(() => { document.body.innerHTML = '' })
function header() {
  return mount(TodoListHeader, { attachTo: document.body, props: {
    heading: '所有', count: 0, selection: 'all', navigationCollapsed: false, creating: false,
    error: '', syncError: '', destination: '收集箱', quick: '输入中的任务', view: defaultTodoView(),
    display: { date: 'time', detail: 'sidebar', sync: true },
  }, global: { stubs: { TodayDates: true, TodoViewControls: true } } })
}
function keyboard(element: Element, key: string, data: KeyboardEventInit = {}) {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...data })
  element.dispatchEvent(event)
  return event
}

it('快速添加在组合输入及229确认期间不提交，组合结束后正常提交一次', async () => {
  const wrapper = header(), input = wrapper.get('input[aria-label="添加任务"]')
  await input.trigger('compositionstart')
  expect(keyboard(input.element, 'Enter').defaultPrevented).toBe(true)
  await wrapper.get('.quick-add').trigger('submit')
  expect(wrapper.emitted('add')).toBeUndefined()
  await input.trigger('compositionend')
  expect(keyboard(input.element, 'Enter', { keyCode: 229 }).defaultPrevented).toBe(true)
  expect(wrapper.emitted('add')).toBeUndefined()
  await wrapper.get('.quick-add').trigger('submit')
  expect(wrapper.emitted('add')).toHaveLength(1)
})

it.each(['', '保存失败，输入已保留'])('保存结束恢复快速输入焦点（%s）', async error => {
  const wrapper = header(), input = wrapper.get<HTMLInputElement>('[aria-label="添加任务"]')
  input.element.focus()
  await wrapper.setProps({ creating: true })
  input.element.blur()
  await wrapper.setProps({ creating: false, error, quick: error ? '保留任务' : '' })
  await flushPromises()
  expect(document.activeElement).toBe(input.element)
})

it('保存期间主动切换到搜索后，不抢回快速输入焦点', async () => {
  const wrapper = header(), input = wrapper.get<HTMLInputElement>('[aria-label="添加任务"]')
  input.element.focus(); await wrapper.setProps({ creating: true })
  const search = wrapper.get<HTMLInputElement>('[aria-label="搜索任务"]')
  search.element.focus()
  await wrapper.setProps({ creating: false, quick: '' }); await flushPromises()
  expect(document.activeElement).toBe(search.element)
})

it('任务已创建但标签失败时保留标题锁定，焦点落在标签重试入口', async () => {
  const wrapper = header(), input = wrapper.get<HTMLInputElement>('[aria-label="添加任务"]')
  input.element.focus(); await wrapper.setProps({ creating: true }); input.element.blur()
  await wrapper.setProps({ creating: false, saved: true, error: '任务已保存，标签读取失败，请重试' })
  await flushPromises()
  expect(input.element.disabled).toBe(true)
  const retry = wrapper.get<HTMLButtonElement>('.quick-add button')
  expect(retry.text()).toBe('重试标签')
  expect(document.activeElement).toBe(retry.element)
  await retry.trigger('click')
  expect(wrapper.emitted('add')).toHaveLength(1)
})

it('清单改名直接聚焦名称，输入法不保存或关闭，失败保留输入并可重试', async () => {
  const data = useDataStore(), project = data.projects[0]!
  const update = vi.spyOn(data, 'updateProject').mockResolvedValueOnce(false).mockResolvedValue(true)
  const wrapper = mount(TaskListDialog, { attachTo: document.body, props: { project } })
  await flushPromises()
  const dialog = new DOMWrapper(document.querySelector('[role="dialog"]')!)
  const input = dialog.get<HTMLInputElement>('[aria-label="清单名称"]')
  expect(document.activeElement).toBe(input.element)
  await input.setValue('修改后的清单')
  await input.trigger('compositionstart')
  expect(keyboard(input.element, 'Escape').defaultPrevented).toBe(true)
  await dialog.get('form').trigger('submit')
  expect(update).not.toHaveBeenCalled(); expect(wrapper.emitted('close')).toBeUndefined()
  await input.trigger('compositionend')
  expect(keyboard(input.element, 'Enter', { keyCode: 229 }).defaultPrevented).toBe(true)
  await dialog.get('form').trigger('submit'); await flushPromises()
  expect(dialog.get('[role="alert"]').text()).toContain('输入已保留')
  expect(input.element.value).toBe('修改后的清单')
  expect(document.activeElement).toBe(input.element)
  await dialog.get('form').trigger('submit'); await flushPromises()
  expect(update).toHaveBeenLastCalledWith(project.id, { name: '修改后的清单' })
  expect(wrapper.emitted('saved')).toEqual([[project.id]])
})

it('清单保存期间主动移动焦点，失败后保持用户选择的焦点', async () => {
  const data = useDataStore()
  let finish!: (saved: boolean) => void
  vi.spyOn(data, 'updateProject').mockReturnValue(new Promise(resolve => { finish = resolve }))
  mount(TaskListDialog, { attachTo: document.body, props: { project: data.projects[0]! } })
  await flushPromises()
  const dialog = new DOMWrapper(document.querySelector('[role="dialog"]')!)
  await dialog.get('form').trigger('submit')
  const nextControl = document.createElement('button'); document.body.append(nextControl); nextControl.focus()
  finish(false); await flushPromises()
  expect(dialog.get('[role="alert"]').text()).toContain('输入已保留')
  expect(document.activeElement).toBe(nextControl)
})

it('同成员切换账号也关闭旧清单，忽略未完成保存的回执', async () => {
  const data = useDataStore(), session = useSessionStore()
  session.account = { accountId: 'first', member: { id: 'same-member' } } as never
  let finish!: (saved: boolean) => void
  vi.spyOn(data, 'updateProject').mockReturnValue(new Promise(resolve => { finish = resolve }))
  const wrapper = mount(TaskListDialog, { attachTo: document.body, props: { project: data.projects[0]! } })
  await flushPromises()
  await new DOMWrapper(document.querySelector('form#task-list-form')!).trigger('submit')
  session.account = { accountId: 'second', member: { id: 'same-member' } } as never
  await flushPromises()
  expect(wrapper.emitted('close')).toHaveLength(1)
  finish(true); await flushPromises()
  expect(wrapper.emitted('saved')).toBeUndefined()
})
