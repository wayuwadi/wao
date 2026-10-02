import { beforeEach, describe, expect, it, vi, afterEach } from 'vitest'
import {
  safeAuthTarget,
  saveAuthHandoff,
  receiveAuthHandoff,
  consumeAuthPractice,
  authReturnTarget,
} from '@/composables/breathingAuth'
import { BreathingSession, readPracticeSnapshot } from '@/composables/breathingSession'
const practice = { mode: 'sitting' as const, total: 60000, remaining: 43125, inhale: 4, exhale: 6 }
beforeEach(() => {
  sessionStorage.clear()
  consumeAuthPractice()
  vi.useFakeTimers()
  vi.setSystemTime(1000000)
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})
describe('有边界的认证恢复', () => {
  it('保存最小快照，匹配 state 后一次性恢复，认证期间不扣时', () => {
    expect(
      saveAuthHandoff('/breathe', practice, 'https://auth.example/authorize?state=nonce'),
    ).toBe(true)
    vi.advanceTimersByTime(120000)
    receiveAuthHandoff('nonce')
    expect(sessionStorage.length).toBe(0)
    expect(authReturnTarget()).toBe('/breathe')
    const snapshot = consumeAuthPractice()!
    const timer = new BreathingSession()
    timer.restore(snapshot.total, snapshot.remaining)
    expect(timer.state).toBe('paused')
    expect(timer.left(Date.now() + 90000)).toBe(43125)
    expect(consumeAuthPractice()).toBeNull()
    receiveAuthHandoff('nonce')
    expect(consumeAuthPractice()).toBeNull()
  })
  it.each(['wrong', 'expired'])('拒绝 %s 回调', (reason) => {
    saveAuthHandoff('/breathe', practice, 'https://auth.example/?state=nonce')
    if (reason === 'expired') vi.advanceTimersByTime(600001)
    receiveAuthHandoff(reason === 'wrong' ? 'other' : 'nonce')
    expect(consumeAuthPractice()).toBeNull()
  })
  it('保存受保护目标的查询和 hash，默认回调保持原工作区兼容性', () => {
    saveAuthHandoff('/?run=run-1&console=1#details', null, 'https://auth.example/?state=n')
    receiveAuthHandoff('n')
    expect(authReturnTarget()).toBe('/?run=run-1&console=1#details')
    consumeAuthPractice()
    expect(authReturnTarget()).toBe('/')
  })
  it.each([
    'https://evil.test',
    '//evil.test',
    '/\\evil.test',
    '/auth/claim',
    '/login?redirect=/login',
    '/connection-recovery',
    '/\ninvalid',
  ])('拒绝危险/循环目标 %s', (target) => {
    expect(safeAuthTarget(target)).toBe('/breathe')
  })
  it('损坏与禁用的存储不抛错，不伪报可恢复', () => {
    sessionStorage.setItem('wao-breathing-auth:v1', '{bad')
    expect(() => receiveAuthHandoff('x')).not.toThrow()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    expect(saveAuthHandoff('/breathe', practice, 'https://auth.example/?state=n')).toBe(false)
  })
  it('只接受完整、有界快照，不带入账号数据', () => {
    expect(readPracticeSnapshot({ ...practice, account: 'private' })).toEqual(practice)
    for (const changed of [
      { remaining: -1 },
      { total: 0 },
      { remaining: Infinity },
      { remaining: 60001 },
      { inhale: 20 },
      { mode: 'unknown' },
    ]) {
      expect(readPracticeSnapshot({ ...practice, ...changed })).toBeNull()
    }
  })
})
