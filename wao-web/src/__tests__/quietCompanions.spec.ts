import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cancelCompanionResponse, blockCompanions, captureCompanionTicket, companionResponse, resetCompanionContext, respondWithCompanion, setCompanionQuiet } from '@/composables/quietCompanions'

let button: HTMLButtonElement
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(100_000)
  document.body.innerHTML = '<button>保存</button><input>'
  button = document.querySelector('button')!
  vi.spyOn(button, 'getBoundingClientRect').mockReturnValue(new DOMRect(200, 300, 80, 32))
  resetCompanionContext(crypto.randomUUID())
  setCompanionQuiet(false)
  button.focus()
})
afterEach(() => { blockCompanions('version', false); vi.restoreAllMocks(); vi.useRealTimers() })
describe('轻回应的打扰边界', () => {
  it('已出现的完成回应遇到撤销也立即退出', () => {
    respondWithCompanion('task', captureCompanionTicket(button))
    expect(companionResponse.value?.kind).toBe('task')
    cancelCompanionResponse('task')
    expect(companionResponse.value).toBeNull()
  })
  it('没有用户操作、跨路由和超过五秒的结果不补播', () => {
    expect(respondWithCompanion('note')).toBe(false)
    const ticket = captureCompanionTicket(button)
    resetCompanionContext()
    expect(respondWithCompanion('note', ticket)).toBe(false)
    const stale = captureCompanionTicket(button)
    vi.advanceTimersByTime(5001)
    expect(respondWithCompanion('note', stale)).toBe(false)
  })
  it('同时最多一个，同类三十秒冷却，成员切换后隔离', () => {
    expect(respondWithCompanion('note', captureCompanionTicket(button))).toBe(true)
    expect(respondWithCompanion('task', captureCompanionTicket(button))).toBe(false)
    companionResponse.value = null
    expect(respondWithCompanion('note', captureCompanionTicket(button))).toBe(false)
    vi.advanceTimersByTime(30_000)
    expect(respondWithCompanion('note', captureCompanionTicket(button))).toBe(true)
    const old = captureCompanionTicket(button)
    resetCompanionContext('another-user')
    expect(respondWithCompanion('task', old)).toBe(false)
  })
  it('输入、版本面板、安静模式和后台均不表演', () => {
    const ticket = captureCompanionTicket(button)
    document.querySelector('input')!.focus()
    expect(respondWithCompanion('note', ticket)).toBe(false)
    button.focus()
    blockCompanions('version', true)
    expect(respondWithCompanion('note', ticket)).toBe(false)
    blockCompanions('version', false)
    setCompanionQuiet(true)
    expect(captureCompanionTicket(button)).toBeUndefined()
    setCompanionQuiet(false)
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
    expect(respondWithCompanion('note', ticket)).toBe(false)
  })
  it('空白空间每个成员每次标签页会话只回应一次', () => {
    expect(respondWithCompanion('empty', captureCompanionTicket(button))).toBe(true)
    resetCompanionContext()
    vi.advanceTimersByTime(30_001)
    expect(respondWithCompanion('empty', captureCompanionTicket(button))).toBe(false)
  })
})
