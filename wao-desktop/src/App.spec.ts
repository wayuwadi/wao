// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App.vue'

const mocks = vi.hoisted(() => ({
  findUpdate: vi.fn(),
  hide: vi.fn(),
  openUrl: vi.fn(),
}))

vi.mock('@tauri-apps/api/app', () => ({ getVersion: vi.fn().mockResolvedValue('0.1.1') }))
vi.mock('@tauri-apps/api/event', () => ({ listen: vi.fn().mockResolvedValue(vi.fn()) }))
vi.mock('@tauri-apps/api/window', () => ({
  getCurrentWindow: () => ({
    hide: mocks.hide,
    onMoved: vi.fn().mockResolvedValue(vi.fn()),
    outerPosition: vi.fn().mockResolvedValue({ x: 100, y: 100 }),
    startDragging: vi.fn().mockResolvedValue(undefined),
  }),
}))
vi.mock('@tauri-apps/plugin-opener', () => ({ openUrl: mocks.openUrl }))
vi.mock('./update', () => ({
  findUpdate: mocks.findUpdate,
  installUpdate: vi.fn(),
}))

describe('desktop pet interaction', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    mocks.findUpdate.mockResolvedValue({ update: null, state: { phase: 'current' } })
    mocks.hide.mockResolvedValue(undefined)
    mocks.openUrl.mockResolvedValue(undefined)
  })

  afterEach(() => {
    vi.clearAllMocks()
    vi.useRealTimers()
  })

  it('starts as a peeking pet, wakes on hover and opens the real feature list on click', async () => {
    const wrapper = mount(App)
    await flushPromises()

    expect(wrapper.get('.pet-stage').classes()).toContain('is-resting')
    expect(wrapper.get('.pet-dock').classes()).toContain('resting')
    expect(wrapper.find('[aria-label="桌面宠物快捷操作"]').exists()).toBe(false)

    await wrapper.get('.pet').trigger('mouseenter')
    expect(wrapper.get('.pet-stage').classes()).not.toContain('is-resting')
    expect(wrapper.find('[aria-label="桌面宠物快捷操作"]').exists()).toBe(true)

    await wrapper.get('.pet').trigger('click')
    const panel = wrapper.get('[aria-label="桌面宠物功能清单"]')
    expect(panel.text()).toContain('3 项真实可用')
    expect(panel.text()).toContain('打开 WAO')
    expect(panel.text()).toContain('检查更新')
    expect(panel.text()).toContain('暂时隐藏')
    expect(wrapper.find('[aria-label="桌面宠物快捷操作"]').exists()).toBe(false)

    wrapper.unmount()
  })

  it('keeps every listed function connected to its existing real action', async () => {
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('.pet').trigger('mouseenter')
    await wrapper.get('.pet').trigger('click')

    const featureButtons = wrapper.findAll('.feature-list > button')
    await featureButtons[0].trigger('click')
    expect(mocks.openUrl).toHaveBeenCalledWith('https://wao.wayuwa.cn')

    await wrapper.get('.pet').trigger('click')
    await wrapper.findAll('.feature-list > button')[1].trigger('click')
    await flushPromises()
    expect(mocks.findUpdate).toHaveBeenCalledOnce()
    expect(wrapper.get('.update-panel').text()).toContain('最新版本')

    await wrapper.get('.pet').trigger('click')
    await wrapper.findAll('.feature-list > button')[2].trigger('click')
    await flushPromises()
    expect(mocks.hide).toHaveBeenCalledOnce()
    expect(wrapper.get('.pet-stage').classes()).toContain('is-resting')

    wrapper.unmount()
  })

  it('returns to the peeking rest state after forty-five seconds without an open panel', async () => {
    const wrapper = mount(App)
    await flushPromises()
    await wrapper.get('.pet').trigger('mouseenter')
    expect(wrapper.get('.pet-stage').classes()).not.toContain('is-resting')

    await vi.advanceTimersByTimeAsync(45_000)
    expect(wrapper.get('.pet-stage').classes()).toContain('is-resting')
    expect(wrapper.find('[aria-label="桌面宠物快捷操作"]').exists()).toBe(false)

    wrapper.unmount()
  })
})
