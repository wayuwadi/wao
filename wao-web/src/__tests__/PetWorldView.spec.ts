import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import PetWorldView from '@/views/PetWorldView.vue'

const scene = vi.hoisted(() => ({ render: vi.fn(), dispose: vi.fn(), create: vi.fn() }))
vi.mock('@/features/pet-world/scene', () => ({ createWorldScene: scene.create }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ userId: 'world-test-member' }) }))
describe('小世界真实输入和生命周期', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    scene.render.mockClear()
    scene.dispose.mockClear()
    scene.create.mockReset().mockResolvedValue(scene)
    vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) =>
      setTimeout(() => callback(performance.now()), 16),
    )
    vi.stubGlobal('cancelAnimationFrame', (id: number) => clearTimeout(id))
  })
  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
    vi.useRealTimers()
  })
  it('按住键连续移动，松开保存，失焦停止，退出释放资源', async () => {
    const wrapper = mount(PetWorldView, { attachTo: document.body })
    await flushPromises()
    await vi.advanceTimersByTimeAsync(16)
    const host = wrapper.get('.world-canvas')
    await host.trigger('keydown', { code: 'KeyW' })
    await vi.advanceTimersByTimeAsync(400)
    expect(Number(host.attributes('data-player-z'))).toBeLessThan(4)
    window.dispatchEvent(new KeyboardEvent('keyup', { code: 'KeyW' }))
    await vi.advanceTimersByTimeAsync(32)
    expect(host.attributes('data-moving')).toBe('false')
    const saved = JSON.parse(localStorage.getItem('wao-pet-world-v1:world-test-member')!)
    expect(saved.z).toBeLessThan(4)
    await host.trigger('keydown', { code: 'KeyD' })
    await vi.advanceTimersByTimeAsync(100)
    await host.trigger('blur')
    const x = host.attributes('data-player-x')
    await vi.advanceTimersByTimeAsync(200)
    expect(host.attributes('data-player-x')).toBe(x)
    const signal = scene.create.mock.calls[0]![1] as AbortSignal
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
    expect(scene.dispose).toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })
  it('恢复成员存档，写入失败可重试且不丢失当前坐标', async () => {
    localStorage.setItem('wao-pet-world-v1:world-test-member', '{"version":1,"x":2,"z":4}')
    const wrapper = mount(PetWorldView)
    await flushPromises()
    expect(wrapper.get('.world-canvas').attributes('data-player-x')).toBe('2.000')
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota')
    })
    await wrapper.get('.world-canvas').trigger('blur')
    expect(wrapper.text()).toContain('位置未保存')
    storage.mockRestore()
    await wrapper.get('.world-footer button').trigger('click')
    expect(wrapper.text()).toContain('位置已保存')
    expect(JSON.parse(localStorage.getItem('wao-pet-world-v1:world-test-member')!).x).toBe(2)
    wrapper.unmount()
  })
  it('加载失败可重新进入，退出后晚到的加载结果不能重启场景', async () => {
    scene.create.mockRejectedValueOnce(new Error('WebGL unavailable'))
    const wrapper = mount(PetWorldView)
    await flushPromises()
    expect(wrapper.text()).toContain('暂时没能进入庭院')
    await wrapper.get('.world-overlay button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.world-canvas').attributes('data-world-state')).toBe('ready')
    wrapper.unmount()
    let resolve: (value: typeof scene) => void = () => {}
    scene.create.mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r
        }),
    )
    const late = mount(PetWorldView)
    await flushPromises()
    late.unmount()
    const calls = scene.render.mock.calls.length
    resolve(scene)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(100)
    expect(scene.render.mock.calls.length).toBe(calls)
    expect(vi.getTimerCount()).toBe(0)
  })
})
