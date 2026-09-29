import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { useLoginSound } from '@/composables/useLoginSound'

describe('登录音效资源与静音', () => {
  it('用户开启前不创建音频，静音暂停，卸载释放', async () => {
    const oscillator = {
      frequency: { setValueAtTime: vi.fn() },
      connect: vi.fn(),
      disconnect: vi.fn(),
      start: vi.fn(),
      stop: vi.fn(),
    }
    const gain = {
      gain: {
        setValueAtTime: vi.fn(),
        linearRampToValueAtTime: vi.fn(),
        exponentialRampToValueAtTime: vi.fn(),
      },
      connect: vi.fn(),
      disconnect: vi.fn(),
    }
    const context = {
      state: 'running',
      currentTime: 1,
      destination: {},
      resume: vi.fn().mockResolvedValue(undefined),
      suspend: vi.fn().mockResolvedValue(undefined),
      close: vi.fn().mockResolvedValue(undefined),
      createOscillator: vi.fn(() => oscillator),
      createGain: vi.fn(() => gain),
    }
    const Audio = vi.fn(function () {
      return context
    })
    vi.stubGlobal('AudioContext', Audio)
    let sound!: ReturnType<typeof useLoginSound>
    const wrapper = mount({
      setup() {
        sound = useLoginSound()
        return () => null
      },
    })
    sound.play('buddy')
    expect(Audio).not.toHaveBeenCalled()
    await sound.toggle()
    expect(sound.enabled.value).toBe(true)
    expect(oscillator.start).toHaveBeenCalled()
    await sound.toggle()
    expect(context.suspend).toHaveBeenCalledOnce()
    const count = context.createOscillator.mock.calls.length
    sound.play('click')
    expect(context.createOscillator).toHaveBeenCalledTimes(count)
    wrapper.unmount()
    expect(context.close).toHaveBeenCalledOnce()
    vi.unstubAllGlobals()
  })
  it('浏览器不支持音频也不抛出认证错误', async () => {
    vi.stubGlobal('AudioContext', undefined)
    let sound!: ReturnType<typeof useLoginSound>
    const wrapper = mount({
      setup() {
        sound = useLoginSound()
        return () => null
      },
    })
    await sound.toggle()
    expect(sound.enabled.value).toBe(false)
    expect(sound.unavailable.value).toBe(true)
    wrapper.unmount()
    vi.unstubAllGlobals()
  })
})
