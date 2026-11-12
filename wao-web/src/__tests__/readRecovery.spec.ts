import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createReadRecovery, isTransientReadFailure } from '@/utils/readRecovery'

beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())
const timeout = () => Object.assign(new Error('timeout of 10000ms exceeded'), { code: 'ECONNABORTED' })

it('仅临时读故障自动恢复，权限、取消与业务校验错误不入队', () => {
  for (const cause of [timeout(), new Error('Network Error'), { status: 503 }, { response: { status: 504 } }]) {
    expect(isTransientReadFailure(cause)).toBe(true)
  }
  for (const cause of [{ status: 401 }, { status: 403 }, { status: 404 }, { code: 'ERR_CANCELED' }, new Error('字段无效')]) {
    expect(isTransientReadFailure(cause)).toBe(false)
  }
})

it('持续失败按1/3/10/30秒退避，读成功后停止', async () => {
  const pending = new Set<string>()
  const retry = vi.fn(async () => recovery.queue('knowledge', timeout()))
  const recovery = createReadRecovery({ pending, identity: () => 'a', available: () => true, failed: () => true, retry })
  recovery.queue('knowledge', timeout())
  for (const [index, delay] of [1000, 3000, 10000, 30000, 30000].entries()) {
    await vi.advanceTimersByTimeAsync(delay - 1)
    expect(retry).toHaveBeenCalledTimes(index)
    await vi.advanceTimersByTimeAsync(1)
    expect(retry).toHaveBeenCalledTimes(index + 1)
  }
  recovery.succeeded('knowledge')
  await vi.advanceTimersByTimeAsync(60000)
  expect(retry).toHaveBeenCalledTimes(5)
  recovery.dispose()
})

it('隐藏或离线时暂停，恢复后立即续读，不积累定时器', async () => {
  let available = false
  const pending = new Set<string>()
  const retry = vi.fn(async () => {})
  const recovery = createReadRecovery({ pending, identity: () => 'a', available: () => available, failed: () => true, retry })
  recovery.queue('tasks', timeout())
  await vi.advanceTimersByTimeAsync(60000)
  expect(retry).not.toHaveBeenCalled()
  expect(vi.getTimerCount()).toBe(0)
  available = true
  recovery.resume()
  await vi.advanceTimersByTimeAsync(0)
  expect(retry).toHaveBeenCalledOnce()
  recovery.dispose()
})

it('串行重试失败模块，同一模块入队多次也只有一个请求', async () => {
  let finish!: () => void
  const pending = new Set<string>()
  const retry = vi.fn().mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve })).mockResolvedValue(undefined)
  const recovery = createReadRecovery({ pending, identity: () => 'a', available: () => true, failed: () => true, retry })
  recovery.queue('knowledge', timeout())
  recovery.queue('knowledge', timeout())
  recovery.queue('files', timeout())
  await vi.advanceTimersByTimeAsync(1000)
  expect(retry.mock.calls).toEqual([['knowledge']])
  finish()
  await vi.advanceTimersByTimeAsync(0)
  expect(retry.mock.calls).toEqual([['knowledge'], ['files']])
  recovery.dispose()
})

it('账号切换取消旧队列，旧在途请求失败不能重新入队', async () => {
  let reject!: (cause: Error) => void
  let identity = 'a'
  const pending = new Set<string>()
  const retry = vi.fn(() => new Promise<void>((_resolve, fail) => { reject = fail }))
  const recovery = createReadRecovery({ pending, identity: () => identity, available: () => true, failed: () => true, retry })
  recovery.queue('knowledge', timeout())
  await vi.advanceTimersByTimeAsync(1000)
  identity = 'b'
  recovery.reset()
  reject(timeout())
  await vi.advanceTimersByTimeAsync(60000)
  expect(pending.size).toBe(0)
  expect(retry).toHaveBeenCalledOnce()
  recovery.dispose()
})

it('释放后迟到失败不能重新启动自动读取', async () => {
  const pending = new Set<string>()
  const retry = vi.fn(async () => {})
  const recovery = createReadRecovery({ pending, identity: () => 'a', available: () => true, failed: () => true, retry })
  recovery.queue('knowledge', timeout())
  recovery.dispose()
  recovery.queue('knowledge', timeout())
  await vi.advanceTimersByTimeAsync(60000)
  expect(retry).not.toHaveBeenCalled()
})
