import { beforeEach, describe, expect, it, vi } from 'vitest'
const get = vi.hoisted(() => vi.fn())
vi.mock('@/api/http', () => ({ http: { get }, }))
import { PreviousTodoWriteRecovered, saveTodoWithReceipt } from '@/api/dayWorkspace'

describe('日操作回执恢复', () => {
  beforeEach(() => { localStorage.clear(); vi.clearAllMocks() })
  it('响应丢失后查询提交结果，不重复写入', async () => {
    const send = vi.fn().mockRejectedValue(new Error('response lost'))
    get.mockResolvedValue({ data: { data: { status: 'COMMITTED', result: { task: 'one' } } } })
    expect(await saveTodoWithReceipt('s', 'account', { value: 1 }, send, () => 'account')).toEqual({ task: 'one' })
    expect(send).toHaveBeenCalledOnce()
    expect(localStorage.length).toBe(0)
  })
  it('未知提交保留精确请求，刷新后的重试沿用同一个操作号', async () => {
    const send = vi.fn().mockRejectedValue(new Error('offline'))
    get.mockResolvedValue({ data: { data: { status: 'UNCONFIRMED' } } })
    await expect(saveTodoWithReceipt('s', 'account', { value: 1 }, send, () => 'account')).rejects.toThrow('待确认')
    const operation = send.mock.calls[0]![0]
    send.mockResolvedValue({ value: 'saved' })
    await saveTodoWithReceipt('s', 'account', { value: 1 }, send, () => 'account')
    expect(send.mock.calls[1]![0]).toBe(operation)
    expect(send.mock.calls[1]![1]).toEqual({ value: 1 })
  })
  it('旧提交成功不能被解释为后来输入已保存', async () => {
    localStorage.setItem('wao-todo-operation-v1:account:s', JSON.stringify({ operation: 'old', request: { value: 1 } }))
    get.mockResolvedValue({ data: { data: { status: 'COMMITTED', result: { value: 1 } } } })
    const send = vi.fn()
    await expect(saveTodoWithReceipt('s', 'account', { value: 2 }, send, () => 'account')).rejects.toBeInstanceOf(PreviousTodoWriteRecovered)
    expect(send).not.toHaveBeenCalled()
  })
  it('版本拒绝允许纠正后新操作，成员切换保留原操作', async () => {
    await expect(saveTodoWithReceipt('s', 'account', {}, vi.fn().mockRejectedValue({ status: 409 }), () => 'account')).rejects.toEqual({ status: 409 })
    expect(localStorage.length).toBe(0)
    let account = 'account'
    const send = vi.fn().mockImplementation(async () => { account = 'other'; return { saved: true } })
    await expect(saveTodoWithReceipt('s', 'account', {}, send, () => account)).rejects.toThrow('账号已变化')
    expect(localStorage.length).toBe(1)
  })
})
