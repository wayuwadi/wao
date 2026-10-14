import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ProjectDetailView from '@/views/ProjectDetailView.vue'
import { seedDataStore } from './helpers/seedDataStore'
import { useDataStore } from '@/stores/data'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { SUBTASK_DRAG_TYPE } from '@/utils/subtaskTree'

enableAutoUnmount(afterEach)
vi.mock('@/api/favorites', async original => ({ ...await original<object>(), fetchPreference: vi.fn().mockResolvedValue(''), savePreference: vi.fn().mockResolvedValue(undefined) }))
beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); seedDataStore(); vi.restoreAllMocks() })

async function open() {
  const data = useDataStore(), original = data.tasks[0]!
  data.tasks = [{ ...original, id: 'a', title: '甲任务', project: 'p1', status: '待办', parentTaskId: null, subs: [{ id: 's1', text: '普通子事项', done: false }] },
    { ...original, id: 'b', title: '乙任务', project: 'p1', status: '待办', parentTaskId: null, subs: [] }]
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:id', component: { template: '<div />' } }] })
  await router.push('/projects/p1')
  const wrapper = mount(ProjectDetailView, { global: { plugins: [router] }, attachTo: document.body })
  await flushPromises()
  return { wrapper, data }
}
function transfer(sub = false) {
  return { effectAllowed: '', setData: vi.fn(), getData: (type: string) => sub && type === SUBTASK_DRAG_TYPE ? JSON.stringify({ taskId: 'a', subId: 's1' }) : '' }
}

it('行中央调整归属，边缘仍排序，拖到主任务区域解除父级', async () => {
  const { wrapper, data } = await open()
  const move = vi.spyOn(data, 'moveTask').mockImplementation(async (id, parent) => { data.taskById(id)!.parentTaskId = parent })
  const order = vi.spyOn(useTaskOrderStore(), 'move').mockResolvedValue()
  const target = wrapper.get('[aria-label="任务：乙任务"]')
  vi.spyOn(target.element, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 100, 600, 40))
  await wrapper.get('[aria-label="任务：甲任务"]').trigger('dragstart', { dataTransfer: transfer() })
  await target.trigger('dragover', { clientY: 120 })
  expect(target.classes()).toContain('drop-inside')
  await target.trigger('drop', { clientY: 120, dataTransfer: transfer() }); await flushPromises()
  expect(move).toHaveBeenLastCalledWith('a', 'b'); expect(order).not.toHaveBeenCalled()
  await wrapper.get('[aria-label="任务：甲任务"]').trigger('dragstart', { dataTransfer: transfer() })
  await wrapper.get('.task-root-drop').trigger('drop', { dataTransfer: transfer() }); await flushPromises()
  expect(move).toHaveBeenLastCalledWith('a', null)
  await wrapper.get('[aria-label="任务：甲任务"]').trigger('dragstart', { dataTransfer: transfer() })
  await target.trigger('drop', { clientY: 101, dataTransfer: transfer() }); await flushPromises()
  expect(order).toHaveBeenCalledWith('project:p1', expect.any(Array), 'a', 'b', false)
})

it('普通子事项拖到主任务区域提升，拖到完整任务行迁移整棵子树', async () => {
  const { wrapper, data } = await open()
  const promote = vi.spyOn(data, 'promoteSubtask').mockResolvedValue(data.tasks[0]!)
  const move = vi.spyOn(data, 'moveSubtask').mockResolvedValue()
  await wrapper.get('.inline-sub-row').trigger('dragstart', { dataTransfer: transfer(true) })
  await wrapper.get('.task-root-drop').trigger('drop', { dataTransfer: transfer(true) }); await flushPromises()
  expect(promote).toHaveBeenCalledWith('a', 's1')
  await wrapper.get('.inline-sub-row').trigger('dragstart', { dataTransfer: transfer(true) })
  await wrapper.get('[aria-label="任务：乙任务"]').trigger('drop', { dataTransfer: transfer(true) }); await flushPromises()
  expect(move).toHaveBeenCalledWith('a', 's1', 'b', null)
})

it('右键和键盘命中正确任务，删除取消不写入，子事项有提升入口', async () => {
  const { wrapper, data } = await open()
  const remove = vi.spyOn(data, 'deleteTask').mockResolvedValue(true)
  await wrapper.get('[aria-label="任务：乙任务"]').trigger('keydown', { key: 'F10', shiftKey: true }); await flushPromises()
  expect(document.querySelector('[role="menu"]')?.getAttribute('aria-label')).toContain('乙任务')
  const deletion = [...document.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')].find(button => button.textContent === '删除任务')!
  deletion.click(); await flushPromises()
  expect(document.querySelector('[role="dialog"]')?.textContent).toContain('乙任务'); expect(remove).not.toHaveBeenCalled()
  const cancel = [...document.querySelectorAll<HTMLButtonElement>('[role="dialog"] button')].find(button => button.textContent === '取消')!
  cancel.click(); await flushPromises(); expect(remove).not.toHaveBeenCalled()
  await wrapper.get('.inline-sub-row').trigger('contextmenu', { clientX: 100, clientY: 100 }); await flushPromises()
  expect(document.querySelector('[role="menu"]')?.textContent).toContain('提升为主任务')
  document.querySelector<HTMLElement>('[role="menu"]')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
  await flushPromises(); expect(document.querySelector('[role="menu"]')).toBeNull()
})

it('移动失败保留原数据并显示可重新操作的错误', async () => {
  const { wrapper, data } = await open(), before = JSON.stringify(data.tasks)
  vi.spyOn(data, 'moveTask').mockRejectedValue(new Error('不能移到自己或后代任务下面'))
  await wrapper.get('[aria-label="任务：甲任务"]').trigger('dragstart', { dataTransfer: transfer() })
  await wrapper.get('[aria-label="任务：乙任务"]').trigger('drop', { dataTransfer: transfer() }); await flushPromises()
  expect(wrapper.get('.task-operation-error').text()).toContain('后代任务')
  expect(JSON.stringify(data.tasks)).toBe(before)
})

it('删除失败保留确认框和重试入口，重试只删除所选任务', async () => {
  const { wrapper, data } = await open()
  const remove = vi.spyOn(data, 'deleteTask').mockResolvedValueOnce(false).mockResolvedValueOnce(true)
  await wrapper.get('[aria-label="任务：乙任务"]').trigger('contextmenu'); await flushPromises()
  const action = [...document.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')].find(button => button.textContent === '删除任务')!
  action.click(); await flushPromises()
  const confirm = [...document.querySelectorAll<HTMLButtonElement>('[role="dialog"] button')].find(button => button.textContent === '确认删除')!
  confirm.click(); await flushPromises()
  expect(document.querySelector('[role="dialog"]')?.textContent).toContain('删除失败')
  const retry = [...document.querySelectorAll<HTMLButtonElement>('[role="dialog"] button')].find(button => button.textContent === '重试删除')!
  retry.click(); await flushPromises()
  expect(remove.mock.calls).toEqual([['b'], ['b']]); expect(document.querySelector('[role="dialog"]')).toBeNull()
})
