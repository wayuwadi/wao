import { afterEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, provide, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { useDocPresence } from '@/composables/docPresence'
import { pageVisibleKey } from '@/composables/pageVisibility'
import { apiDocPresence } from '@/api/board'

vi.mock('@/stores/data', () => ({
  useDataStore: () => ({ apiLive: true, docById: () => ({ rev: 3 }), applyRemoteDoc: vi.fn() }),
}))
vi.mock('@/api/board', () => ({
  apiDocPresence: vi.fn().mockResolvedValue({ peers: [], rev: 3 }),
  apiLeaveDoc: vi.fn().mockResolvedValue(undefined),
}))

describe('保留文档页签的协作心跳', () => {
  afterEach(() => { vi.useRealTimers(); vi.clearAllMocks() })

  it('切到其它工作区页面暂停，回到文档立即同步，浮窗保持同步', async () => {
    vi.useFakeTimers()
    Object.defineProperty(document, 'hidden', { configurable: true, value: false })
    const visible = ref(true)
    const reader = defineComponent({
      setup() { useDocPresence(ref('d1')); return () => null },
    })
    const wrapper = mount(defineComponent({
      components: { reader },
      setup() { provide(pageVisibleKey, visible) },
      template: '<reader />',
    }))
    await flushPromises()
    expect(apiDocPresence).toHaveBeenCalledTimes(1)
    visible.value = false
    await flushPromises()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(apiDocPresence).toHaveBeenCalledTimes(1)
    visible.value = true
    await flushPromises()
    expect(apiDocPresence).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(5_000)
    expect(apiDocPresence).toHaveBeenCalledTimes(3)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(apiDocPresence).toHaveBeenCalledTimes(3)
  })
})
