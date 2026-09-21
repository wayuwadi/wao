import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Task } from '@/types'
import { saveTaskWithReceipt, taskWriteKey, TaskWriteUnconfirmed, type TaskWriteTransport } from '@/api/taskWriteReceipt'

const task = { id: 'receipt-test', title: '草稿', editVersion: 'before', desc: '正文' } as Task
const saved = { ...task, editVersion: 'after', number: 123 }
const owner = () => 'account-a'
function transport(): TaskWriteTransport {
  return { lookup: vi.fn().mockResolvedValue({ status: 'UNCONFIRMED', task: null }), write: vi.fn().mockResolvedValue(saved) }
}
beforeEach(() => localStorage.clear())

describe('持久任务操作回执', () => {
  it('先写本机操作记录，再发送；收到服务端回执才清除', async () => {
    const api = transport()
    vi.mocked(api.write).mockImplementation(async (_id, operation) => {
      expect(JSON.parse(localStorage.getItem(taskWriteKey('account-a', task.id))!).operation).toBe(operation)
      return saved
    })
    expect(await saveTaskWithReceipt(task, 'account-a', api, owner)).toEqual(saved)
    expect(localStorage.getItem(taskWriteKey('account-a', task.id))).toBeNull()
  })

  it('提交成功但响应丢失时查询原操作，不重复写入', async () => {
    const api = transport()
    vi.mocked(api.write).mockRejectedValue(new Error('connection lost'))
    vi.mocked(api.lookup).mockResolvedValue({ status: 'COMMITTED', task: saved })
    expect(await saveTaskWithReceipt(task, 'account-a', api, owner)).toEqual(saved)
    expect(api.write).toHaveBeenCalledTimes(1)
    expect(api.lookup).toHaveBeenCalledWith(task.id, vi.mocked(api.write).mock.calls[0]![1])
  })

  it('刷新后沿用未确认操作，先查询再用原键原快照重试', async () => {
    const api = transport()
    vi.mocked(api.write).mockRejectedValueOnce(new Error('offline'))
    await expect(saveTaskWithReceipt(task, 'account-a', api, owner)).rejects.toBeInstanceOf(TaskWriteUnconfirmed)
    const operation = JSON.parse(localStorage.getItem(taskWriteKey('account-a', task.id))!)
    expect(await saveTaskWithReceipt(task, 'account-a', api, owner)).toEqual(saved)
    expect(vi.mocked(api.write).mock.calls[1]!.slice(1, 3)).toEqual([operation.operation, task])
  })

  it('旧操作确认后保留新输入，并用已确认版本提交一个新操作', async () => {
    const api = transport()
    vi.mocked(api.write).mockRejectedValueOnce(new Error('offline'))
    await expect(saveTaskWithReceipt(task, 'account-a', api, owner)).rejects.toBeInstanceOf(TaskWriteUnconfirmed)
    const oldId = JSON.parse(localStorage.getItem(taskWriteKey('account-a', task.id))!).operation
    vi.mocked(api.lookup).mockResolvedValue({ status: 'COMMITTED', task: saved })
    const newer = { ...task, desc: '掉线后继续输入' }
    await saveTaskWithReceipt(newer, 'account-a', api, owner)
    const next = vi.mocked(api.write).mock.calls[1]!
    expect(next[1]).not.toBe(oldId)
    expect(next[2]).toMatchObject({ desc: newer.desc, editVersion: 'after' })
  })

  it('查询也失败时不盲目重放，不删除待确认记录', async () => {
    const api = transport()
    vi.mocked(api.write).mockRejectedValueOnce(new Error('offline'))
    await expect(saveTaskWithReceipt(task, 'account-a', api, owner)).rejects.toBeInstanceOf(TaskWriteUnconfirmed)
    vi.mocked(api.lookup).mockRejectedValue(new Error('offline'))
    await expect(saveTaskWithReceipt(task, 'account-a', api, owner)).rejects.toBeInstanceOf(TaskWriteUnconfirmed)
    expect(api.write).toHaveBeenCalledTimes(1)
    expect(localStorage.getItem(taskWriteKey('account-a', task.id))).not.toBeNull()
  })

  it('账号变化时停止发送，不读取另一账号记录', async () => {
    const api = transport()
    await expect(saveTaskWithReceipt(task, 'account-a', api, () => 'account-b')).rejects.toThrow('账号已变化')
    expect(api.write).not.toHaveBeenCalled()
    expect(api.lookup).not.toHaveBeenCalled()
  })

  it('本机存储失败时不发送写入', async () => {
    const api = transport()
    const storage = { getItem: () => null, setItem: () => { throw new Error('quota') } } as unknown as Storage
    await expect(saveTaskWithReceipt(task, 'account-a', api, owner, storage)).rejects.toThrow('本次尚未发送')
    expect(api.write).not.toHaveBeenCalled()
  })

  it('版本冲突保留调用方输入，清除已明确拒绝的操作以便解决冲突', async () => {
    const api = transport()
    vi.mocked(api.write).mockRejectedValue(Object.assign(new Error('conflict'), { status: 409 }))
    await expect(saveTaskWithReceipt(task, 'account-a', api, owner)).rejects.toThrow('conflict')
    expect(localStorage.getItem(taskWriteKey('account-a', task.id))).toBeNull()
    expect(task.desc).toBe('正文')
  })
})
