import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { apiDeleteTask, apiPutTask } from '@/api/board'
import { readTaskDrafts, taskDraftWindowId } from '@/utils/taskDraftStorage'
import { TASKS } from '@/data/fixtures'
import type { Task } from '@/types'

vi.mock('@/api/board', async original => ({ ...await original<object>(), apiPutTask: vi.fn(), apiDeleteTask: vi.fn() }))
beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); vi.clearAllMocks(); vi.useFakeTimers() })
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers() })

it('删除失败恢复任务且不清除未保存的冲突草稿', async () => {
  const data = useDataStore()
  data.tasks = [{ ...structuredClone(TASKS[0]!), id: 'delete-failed', editVersion: 'v1' }]
  data.apiLive = true
  data.patchTask('delete-failed', { desc: '本机不能丢的内容' })
  data.taskConflicts['delete-failed'] = true
  vi.mocked(apiDeleteTask).mockRejectedValueOnce(new Error('离线'))
  expect(await data.deleteTask('delete-failed')).toBe(false)
  expect(data.taskById('delete-failed')?.desc).toBe('本机不能丢的内容')
  expect(readTaskDrafts('', taskDraftWindowId())['delete-failed']?.desc).toBe('本机不能丢的内容')
  expect(data.taskConflicts['delete-failed']).toBe(true)
})

it('初始化尚未确认连接时，既有任务也必须收到服务器删除结果', async () => {
  const data = useDataStore()
  data.tasks = [{ ...structuredClone(TASKS[0]!), id: 'offline-delete', number: 1037 }]
  data.apiLive = false
  vi.mocked(apiDeleteTask).mockRejectedValueOnce(new Error('offline'))
  expect(await data.deleteTask('offline-delete')).toBe(false)
  expect(apiDeleteTask).toHaveBeenCalledWith('offline-delete')
  expect(data.taskById('offline-delete')).toBeDefined()
})

it('删除排在在途保存之后，迟到保存不恢复任务或重建草稿', async () => {
  const data = useDataStore()
  const task = { ...structuredClone(TASKS[0]!), id: 'delete-race', editVersion: 'v1' }
  data.tasks = [task]; data.apiLive = true
  let finish!: (task: Task) => void
  vi.mocked(apiPutTask).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  vi.mocked(apiDeleteTask).mockResolvedValueOnce(undefined)
  data.patchTask(task.id, { desc: '正在保存' })
  const saving = data.flushTask(task.id)
  const deleting = data.deleteTask(task.id)
  expect(apiDeleteTask).not.toHaveBeenCalled()
  finish({ ...task, editVersion: 'v2' })
  await saving
  await vi.advanceTimersByTimeAsync(30)
  expect(await deleting).toBe(true)
  await vi.advanceTimersByTimeAsync(1000)
  expect(apiPutTask).toHaveBeenCalledTimes(1)
  expect(apiDeleteTask).toHaveBeenCalledTimes(1)
  expect(data.taskById(task.id)).toBeUndefined()
  expect(readTaskDrafts('', taskDraftWindowId())[task.id]).toBeUndefined()
})
