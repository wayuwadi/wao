import { describe, expect, it } from 'vitest'
import {
  BreathingSession,
  breathingDefaults,
  readBreathingPreferences,
} from '@/composables/breathingSession'

describe('呼吸计时', () => {
  it('后台回调缺席后仍按真实经过时间到期，不增加额外练习时间', () => {
    const timer = new BreathingSession()
    timer.start(15 * 60000, 1000)
    expect(timer.left(301000)).toBe(600000)
    expect(timer.left(1000000)).toBe(0)
  })
  it('暂停期间不扣时，继续后沿用本轮剩余时间与呼吸相位', () => {
    const timer = new BreathingSession()
    timer.start(300000, 1000)
    timer.pause(4000)
    expect(timer.left(100000)).toBe(297000)
    expect(timer.elapsed(100000)).toBe(3000)
    timer.resume(100000)
    expect(timer.left(101000)).toBe(296000)
    expect(timer.elapsed(101000)).toBe(4000)
    timer.stop()
    expect(timer.state).toBe('idle')
  })
  it('新一轮重置时间，非法时长不开始', () => {
    const timer = new BreathingSession()
    timer.start(Number.NaN, 0)
    expect(timer.state).toBe('idle')
    timer.start(10000, 0)
    timer.stop()
    timer.start(60000, 100000)
    expect(timer.left(100000)).toBe(60000)
  })
})

describe('呼吸偏好边界', () => {
  it('损坏存储退回夜间静默且星河关闭', () => {
    for (const value of [null, '{broken', 'null', '42'])
      expect(readBreathingPreferences(value)).toEqual(breathingDefaults)
  })
  it('限制时长范围，不接受未知枚举或错误类型', () => {
    const prefs = readBreathingPreferences(
      JSON.stringify({
        minutes: 999,
        inhale: -1,
        exhale: '100',
        theme: 'auto',
        scene: 'moving',
        galaxy: 'true',
        words: false,
        color: 'blue',
      }),
    )
    expect(prefs).toMatchObject({
      minutes: 180,
      inhale: 2,
      exhale: 6,
      theme: 'night',
      scene: 'halo',
      galaxy: false,
      words: false,
      color: 'blue',
    })
  })
})
