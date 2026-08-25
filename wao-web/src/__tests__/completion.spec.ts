import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { SETTLE_MS, useCompletionSettle } from '@/composables/completion'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'
import { useUiStore } from '@/stores/ui'

/** 组合式函数带 onBeforeUnmount，必须在组件里用。 */
function useInComponent() {
  let api: ReturnType<typeof useCompletionSettle>
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useCompletionSettle()
        return () => null
      },
    }),
  )
  return { api: api!, wrapper }
}

describe('useCompletionSettle', () => {
  let data: ReturnType<typeof useDataStore>
  let taskId: string

  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    setActivePinia(createPinia())
    data = useDataStore()
    seedDataStore(data)
    taskId = data.tasks.find((t) => t.status !== '已完成')!.id
  })
  afterEach(() => vi.useRealTimers())

  it('勾完成后任务立刻变成已完成，但先停留在缓冲期', () => {
    const { api } = useInComponent()
    api.toggle(taskId)

    expect(data.taskById(taskId)!.status).toBe('已完成')
    expect(api.isSettling(taskId)).toBe(true)
  })

  it('3 秒后缓冲结束，任务才被「已完成」收走', () => {
    const { api } = useInComponent()
    api.toggle(taskId)

    vi.advanceTimersByTime(SETTLE_MS - 1)
    expect(api.isSettling(taskId)).toBe(true)

    vi.advanceTimersByTime(1)
    expect(api.isSettling(taskId)).toBe(false)
  })

  it('缓冲期内再点一次撤销完成，缓冲立即结束', () => {
    const { api } = useInComponent()
    api.toggle(taskId)
    api.toggle(taskId)

    expect(data.taskById(taskId)!.status).toBe('待办')
    expect(api.isSettling(taskId)).toBe(false)
  })

  it('完成任务时触发庆祝动画——和创建任务一样', () => {
    const ui = useUiStore()
    const { api } = useInComponent()

    api.toggle(taskId)

    expect(ui.celebrating).toBeTruthy()
  })

  it('取消完成不触发庆祝', () => {
    const ui = useUiStore()
    const { api } = useInComponent()
    api.toggle(taskId)
    ui.stopCelebrate()

    api.toggle(taskId)

    expect(ui.celebrating).toBeNull()
  })

  it('组件卸载后定时器不再回调，避免操作已销毁的状态', () => {
    const { api, wrapper } = useInComponent()
    api.toggle(taskId)
    wrapper.unmount()

    expect(() => vi.advanceTimersByTime(SETTLE_MS)).not.toThrow()
  })
})
