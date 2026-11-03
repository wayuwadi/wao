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
  w.unmount(); w = mountAdd()
  expect(w.get('input').element.value).toBe('未提交草稿')
  w.unmount()
})
