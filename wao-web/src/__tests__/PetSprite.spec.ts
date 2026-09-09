import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PetSprite from '@/components/assistant/PetSprite.vue'

describe('宠物动画生命周期', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('Image', class { onload?: () => void; onerror?: () => void; set src(_value: string) { this.onload?.() } })
    vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
  })
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })
  it('在同一动作内前进；暂停、恢复和卸载不会遗留动画计时器', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'running-right' } })
    await vi.advanceTimersByTimeAsync(130)
    expect(wrapper.attributes('data-frame')).toBe('1')
    await wrapper.setProps({ paused: true })
    await vi.advanceTimersByTimeAsync(500)
    expect(wrapper.attributes('data-frame')).toBe('0')
    await wrapper.setProps({ paused: false })
    await vi.advanceTimersByTimeAsync(130)
    expect(wrapper.attributes('data-frame')).toBe('1')
    wrapper.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
  it('播放完整挥手后通知父级恢复待机，并尊重减少动态效果', async () => {
    const wrapper = mount(PetSprite, { props: { state: 'waving' } })
    await vi.advanceTimersByTimeAsync(710)
    expect(wrapper.emitted('finished')).toHaveLength(1)
    expect(wrapper.attributes('data-frame')).toBe('3')
    wrapper.unmount()
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    const reduced = mount(PetSprite)
    await vi.advanceTimersByTimeAsync(1000)
    expect(reduced.attributes('data-frame')).toBe('0')
    reduced.unmount()
  })
})
