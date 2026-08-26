import { describe, expect, it } from 'vitest'
import { nextServerSyncDelay } from '@/stores/data'

describe('项目与任务后台同步退避', () => {
  it('从 15 秒逐级退避并封顶 60 秒', () => {
    expect(nextServerSyncDelay(0)).toBe(15_000)
    expect(nextServerSyncDelay(1)).toBe(30_000)
    expect(nextServerSyncDelay(2)).toBe(60_000)
    expect(nextServerSyncDelay(20)).toBe(60_000)
  })

  it('对异常轮次按最快档处理', () => {
    expect(nextServerSyncDelay(-1)).toBe(15_000)
  })
})
