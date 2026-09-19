import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { apiGetTask, apiPutTask } from '@/api/board'
import { readTaskDrafts, taskDraftWindowId } from '@/utils/taskDraftStorage'
import { TASKS } from '@/data/fixtures'
import type { Task } from '@/types'

vi.mock('@/api/board', async original => ({
  ...await original<typeof import('@/api/board')>(), apiPutTask: vi.fn(), apiGetTask: vi.fn(),
}))
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
describe('任务全文保存版本', () => {
  beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); vi.useFakeTimers(); vi.mocked(apiPutTask).mockReset(); vi.mocked(apiGetTask).mockReset() })
  afterEach(() => { vi.clearAllTimers(); vi.useRealTimers() })
  it('全文冲突保留本机内容并停止子任务合并和自动重试', async () => {
    const data = useDataStore(); data.tasks = [{ ...copy(TASKS[0]!), editVersion: 'old' }]
    const id = data.tasks[0]!.id
    vi.mocked(apiPutTask).mockRejectedValue(Object.assign(new Error('任务内容已被其他窗口更新'), { status: 409 }))
    data.patchTask(id, { desc: '本机正文' }); await data.flushTask(id)
    expect(data.taskConflicts[id]).toBe(true)
    expect(data.taskSaveState(id)).toBe('error')
    expect(apiGetTask).not.toHaveBeenCalled()
    data.patchTask(id, { desc: '继续输入仍保留' }); await data.flushTask(id)
    await vi.advanceTimersByTimeAsync(1000)
    expect(apiPutTask).toHaveBeenCalledTimes(1)
    expect(data.tasks[0]!.desc).toBe('继续输入仍保留')
    expect(readTaskDrafts('', taskDraftWindowId())[id]?.desc).toBe('继续输入仍保留')
  })
  it('请求期间继续输入，下次串行提交使用刚确认的完整版本', async () => {
    const data = useDataStore(); data.tasks = [{ ...copy(TASKS[0]!), editVersion: 'v1' }]
    const id = data.tasks[0]!.id; let resolve!: (task: Task) => void
    vi.mocked(apiPutTask).mockImplementationOnce(() => new Promise(r => { resolve = r }))
      .mockImplementationOnce(async (_id, body) => ({ ...body, editVersion: 'v3' }))
    data.patchTask(id, { desc: '第一版' }); const first = data.flushTask(id)
    const submitted = copy(data.tasks[0]!)
    data.patchTask(id, { desc: '第二版' }); resolve({ ...submitted, editVersion: 'v2' })
    await first
    expect(vi.mocked(apiPutTask).mock.calls[1]?.[1]).toMatchObject({ desc: '第二版', editVersion: 'v2' })
    expect(data.tasks[0]!.editVersion).toBe('v3')
  })
})
