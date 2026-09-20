import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises } from '@vue/test-utils'
import { taskTreeRows, taskDropIsNesting } from '@/utils/taskHierarchy'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { apiMoveTask, apiPutTask, apiDeleteTask } from '@/api/board'
vi.mock('@/api/board', async original => ({ ...await original<object>(), apiMoveTask: vi.fn(), apiPutTask: vi.fn(), apiDeleteTask: vi.fn() }))
beforeEach(() => {
  localStorage.clear(); setActivePinia(createPinia()); seedDataStore(); vi.clearAllMocks()
  vi.mocked(apiPutTask).mockImplementation(async (_id, task) => task as never)
})
afterEach(() => vi.restoreAllMocks())
it('层级顺序、折叠与筛选不丢任务', () => {
  const a = { id: 'a' }, b = { id: 'b', parentTaskId: 'a' }, c = { id: 'c', parentTaskId: 'b' }
  expect(taskTreeRows([c,b,a], []).map(r => [r.task.id,r.depth])).toEqual([['a',0],['b',1],['c',2]])
  expect(taskTreeRows([c,b,a], ['b']).map(r => r.task.id)).toEqual(['a','b'])
  expect(taskTreeRows([c], [])).toEqual([{ task: c, depth: 0 }])
  expect(taskDropIsNesting(120,100,40)).toBe(true)
  expect(taskDropIsNesting(101,100,40)).toBe(false)
})
it('移动成功仅合并关系，不覆盖在途正文、附件和编号', async () => {
  const data = useDataStore(), source = data.tasks[0]!, target = data.tasks[1]!
  const originalNumber = source.number, originalFiles = source.files
  let resolve!: (task: typeof source) => void
  vi.mocked(apiMoveTask).mockReturnValueOnce(new Promise(r => resolve = r))
  const pending = data.moveTask(source.id, target.id); await flushPromises()
  data.patchTask(source.id, { desc: '请求中继续编辑' })
  resolve({ ...source, desc: '旧正文', parentTaskId: target.id })
  await pending
  expect(source.parentTaskId).toBe(target.id); expect(source.desc).toBe('请求中继续编辑')
  expect(source.number).toBe(originalNumber); expect(source.files).toEqual(originalFiles)
  await data.flushTask(source.id)
})
it('失败不改变归属，允许重试且阻止重复请求', async () => {
  const data = useDataStore(), source = data.tasks[0]!
  let reject!: (error: Error) => void
  vi.mocked(apiMoveTask).mockReturnValueOnce(new Promise((_r, r) => reject = r))
  const pending = data.moveTask(source.id, 'parent'); await flushPromises()
  await expect(data.moveTask(source.id, 'parent')).rejects.toThrow('正在调整')
  reject(new Error('离线')); await expect(pending).rejects.toThrow('离线')
  expect(source.parentTaskId).toBeUndefined(); expect(data.movingTask).toBe(false)
  vi.mocked(apiMoveTask).mockResolvedValueOnce({ ...source, parentTaskId: 'parent' })
  await data.moveTask(source.id, 'parent'); expect(source.parentTaskId).toBe('parent')
})

it('删除父任务失败保留关系，成功后子任务立即回顶层并保留草稿', async () => {
  const data = useDataStore(), parent = data.tasks[0]!, child = data.tasks[1]!
  data.apiLive = true; child.parentTaskId = parent.id; child.desc = '保留内容'
  vi.mocked(apiDeleteTask).mockRejectedValueOnce(new Error('失败'))
  expect(await data.deleteTask(parent.id)).toBe(false)
  expect(child.parentTaskId).toBe(parent.id)
  vi.mocked(apiDeleteTask).mockResolvedValueOnce(undefined)
  expect(await data.deleteTask(parent.id)).toBe(true)
  expect(child.parentTaskId).toBeNull(); expect(child.desc).toBe('保留内容')
})
