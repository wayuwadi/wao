import { beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import QuadrantQuickAdd from '@/components/task/QuadrantQuickAdd.vue'
import { seedDataStore } from './helpers/seedDataStore'
import { useDataStore } from '@/stores/data'

beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); seedDataStore() })
const mountAdd = () => mount(QuadrantQuickAdd, { props: { priority: 'P1', projectId: 'current-list', label: '重要不紧急' } })

it('在当前清单及对应象限创建，失败后重试同一任务且保留输入', async () => {
  const data = useDataStore()
  const add = vi.spyOn(data, 'addTask').mockImplementation(async (payload, id) => {
    const task = { ...data.tasks[0]!, ...payload, id: id! }
    data.tasks.push(task)
    return { task, persisted: false }
  })
  const flush = vi.spyOn(data, 'flushTask').mockResolvedValue()
  vi.spyOn(data, 'taskSaveState').mockReturnValue('saved')
  const w = mountAdd()
  await w.get('input').setValue('测试象限任务')
  await w.get('form').trigger('submit'); await flushPromises()
  expect(add).toHaveBeenCalledWith(expect.objectContaining({ project: 'current-list', pr: 'P1', title: '测试象限任务' }), expect.any(String))
  expect(w.get('input').element.value).toBe('测试象限任务')
  expect(w.get('[role="alert"]').text()).toContain('重试')
  await w.get('form').trigger('submit'); await flushPromises()
  expect(add).toHaveBeenCalledTimes(1)
  expect(flush).toHaveBeenCalledWith(add.mock.calls[0]![1])
  expect(w.get('input').element.value).toBe('')
  w.unmount()
})

it('空标题不创建，关闭再打开恢复未提交草稿，中文输入确认不提交', async () => {
  const add = vi.spyOn(useDataStore(), 'addTask')
  let w = mountAdd()
  await w.get('input').setValue('  '); await w.get('form').trigger('submit')
  expect(add).not.toHaveBeenCalled()
  await w.get('input').setValue('未提交草稿')
  await w.get('input').trigger('keydown', { key: 'Enter', isComposing: true })
  expect(add).not.toHaveBeenCalled()
  await w.get('input').trigger('keydown', { key: 'Enter', keyCode: 229 })
  await w.get('input').trigger('compositionstart')
  await w.get('input').trigger('keydown', { key: 'Escape' })
  await w.get('form').trigger('submit')
  expect(add).not.toHaveBeenCalled();expect(w.emitted('close')).toBeUndefined()
  await w.get('input').trigger('compositionend')
  w.unmount(); w = mountAdd()
  expect(w.get('input').element.value).toBe('未提交草稿')
  w.unmount()
})

it('展开属性里的标签输入法确认也不会提交整个象限表单', async () => {
  const data = useDataStore()
  const add = vi.spyOn(data, 'addTask').mockResolvedValue({ task: data.tasks[0]!, persisted: false })
  const w = mountAdd()
  try {
    await w.get('input').setValue('等待填写标签')
    const tags = w.get('input[placeholder="用逗号分隔"]')
    await tags.trigger('compositionstart')
    await tags.trigger('keydown', { key: 'Enter' })
    await w.get('form').trigger('submit')
    expect(add).not.toHaveBeenCalled()
    await tags.trigger('compositionend')
    const event = new KeyboardEvent('keydown', { key: 'Enter', keyCode: 229, bubbles: true, cancelable: true })
    tags.element.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    await w.get('form').trigger('submit'); await flushPromises()
    expect(add).toHaveBeenCalledTimes(1)
  } finally { w.unmount() }
})

it.each([true, false])('象限保存结束恢复输入焦点（保存成功：%s）', async persisted => {
  const data = useDataStore()
  let finish!: (result: Awaited<ReturnType<typeof data.addTask>>) => void
  vi.spyOn(data, 'addTask').mockReturnValue(new Promise(resolve => { finish = resolve }))
  const w = mount(QuadrantQuickAdd, { attachTo: document.body, props: { priority: 'P1', projectId: 'current-list', label: '重要不紧急' } })
  try {
    const input = w.get('input')
    await input.setValue('连续输入任务'); input.element.focus()
    await w.get('form').trigger('submit'); input.element.blur()
    finish({ task: data.tasks[0]!, persisted }); await flushPromises()
    expect(document.activeElement).toBe(input.element)
    expect(input.element.value).toBe(persisted ? '' : '连续输入任务')
  } finally { w.unmount() }
})

it('象限保存期间切换到其他输入后，不抢回焦点', async () => {
  const data = useDataStore()
  let finish!: (result: Awaited<ReturnType<typeof data.addTask>>) => void
  vi.spyOn(data, 'addTask').mockReturnValue(new Promise(resolve => { finish = resolve }))
  const w = mount(QuadrantQuickAdd, { attachTo: document.body, props: { priority: 'P1', projectId: 'current-list', label: '重要不紧急' } })
  const nextInput = document.createElement('input'); document.body.append(nextInput)
  try {
    await w.get('input').setValue('连续输入任务')
    await w.get('form').trigger('submit'); nextInput.focus()
    finish({ task: data.tasks[0]!, persisted: true }); await flushPromises()
    expect(document.activeElement).toBe(nextInput)
  } finally { w.unmount(); nextInput.remove() }
})
