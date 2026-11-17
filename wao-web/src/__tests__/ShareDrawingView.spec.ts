import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ShareDrawingView from '@/views/ShareDrawingView.vue'
import { readSharedDrawing, type SharedDrawing } from '@/api/drawingShare'

const token = 'ds_' + 'a'.repeat(43)
vi.mock('vue-router', () => ({ useRoute: () => ({ hash: '#' + token }) }))
vi.mock('@/api/drawingShare', () => ({ readSharedDrawing: vi.fn() }))
vi.mock('@/components/drawings/SharedDrawingCanvas.vue', () => ({
  default: { template: '<div class="test-shared-canvas"></div>' },
}))

const saved: SharedDrawing = {
  title: '测试画布',
  revision: 3,
  updatedAt: '2026-11-17T01:00:00Z',
  expiresAt: null,
  scene: { elements: [] },
}
const read = vi.mocked(readSharedDrawing)

beforeEach(() => {
  vi.useFakeTimers()
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  read.mockReset()
})
afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

it('同一画布使用修订号检查更新，并在撤销后卸载画布', async () => {
  // The shared HTTP client forwards server business errors as Error with a status property.
  read.mockResolvedValueOnce(saved)
    .mockResolvedValueOnce({ ...saved, title: '已改名', scene: null })
    .mockRejectedValueOnce(Object.assign(new Error('链接已撤销'), { status: 404 }))
  const wrapper = mount(ShareDrawingView)
  try {
    await flushPromises()
    await vi.dynamicImportSettled()
    const mountedCanvas = wrapper.get('.test-shared-canvas').element
    expect(read).toHaveBeenCalledWith(token, 0, expect.any(AbortSignal))
    await vi.advanceTimersByTimeAsync(5000)
    expect(read).toHaveBeenLastCalledWith(token, 3, expect.any(AbortSignal))
    expect(wrapper.get('h1').text()).toBe('已改名')
    expect(wrapper.get('.test-shared-canvas').element).toBe(mountedCanvas)
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.get('h1').text()).toBe('草图链接已失效')
    expect(wrapper.find('.test-shared-canvas').exists()).toBe(false)
    await vi.advanceTimersByTimeAsync(5000)
    expect(read).toHaveBeenCalledTimes(3)
  } finally {
    wrapper.unmount()
  }
})

it('链接到期时即使仍在同步也会清除画布', async () => {
  read.mockResolvedValueOnce({ ...saved, expiresAt: new Date(Date.now() + 2000).toISOString() })
  const wrapper = mount(ShareDrawingView)
  try {
    await flushPromises()
    await vi.dynamicImportSettled()
    expect(wrapper.find('.test-shared-canvas').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(2000)
    expect(wrapper.find('.test-shared-canvas').exists()).toBe(false)
    expect(wrapper.get('h1').text()).toBe('草图链接已失效')
  } finally {
    wrapper.unmount()
  }
})
