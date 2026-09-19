import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { apiPutTask } from '@/api/board'
import { TASKS } from '@/data/fixtures'
vi.mock('@/api/board', async original => ({ ...await original<typeof import('@/api/board')>(), apiPutTask: vi.fn() }))
describe('任务本机存储失败', () => {
  beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); vi.useFakeTimers(); vi.mocked(apiPutTask).mockReset() })
  afterEach(() => { vi.restoreAllMocks(); vi.clearAllTimers(); vi.useRealTimers() })
  it('本机存储拒绝写入也会排队保存服务器，不能沿用已保存状态', async () => {
    const data = useDataStore(); data.tasks = JSON.parse(JSON.stringify([TASKS[0]!]))
    const id = data.tasks[0]!.id
    vi.mocked(apiPutTask).mockImplementation(async (_id, body) => body)
    await data.flushTask(id)
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('QuotaExceededError') })
    expect(() => data.patchTask(id, { desc: '本机失败但仍应上传' })).not.toThrow()
    expect(data.taskSaveState(id)).toBe('saving'); expect(data.taskDraftErrors[id]).toBe(true)
    await vi.advanceTimersByTimeAsync(600)
    expect(apiPutTask).toHaveBeenLastCalledWith(id, expect.objectContaining({ desc: '本机失败但仍应上传' }))
    expect(data.taskSaveState(id)).toBe('saved')
    storage.mockRestore()
    data.patchTask(id, { desc: '存储恢复后继续输入' }); await data.flushTask(id)
    expect(data.taskDraftErrors[id]).toBeFalsy()
  })
  it('两处都失败时保留内存和明确错误，重试提交同一份最新内容', async () => {
    const data = useDataStore(); data.tasks = JSON.parse(JSON.stringify([TASKS[0]!]))
    const id = data.tasks[0]!.id
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    vi.mocked(apiPutTask).mockRejectedValueOnce(new Error('offline'))
    data.patchTask(id, { desc: '不能丢失' }); await data.flushTask(id)
    expect(data.taskSaveState(id)).toBe('error'); expect(data.tasks[0]!.desc).toBe('不能丢失')
    vi.mocked(apiPutTask).mockImplementation(async (_id, body) => body)
    await data.flushTask(id)
    expect(data.taskSaveState(id)).toBe('saved')
  })
})
