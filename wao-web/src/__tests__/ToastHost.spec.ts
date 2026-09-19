import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ToastHost from '@/components/ui/ToastHost.vue'
import { clearToasts, toast, pauseToast, useToastHost } from '@/composables/toast'
beforeEach(() => { clearToasts(); vi.useFakeTimers() })
afterEach(() => { clearToasts(); vi.useRealTimers() })
describe('全局操作提示', () => {
  it('普通反馈使用非打断语义并保留既有消失时间', async () => {
    const w = mount(ToastHost)
    toast('已复制')
    await w.vm.$nextTick()
    expect(w.get('[role="status"]').text()).toContain('已复制')
    expect(w.get('.toast > i').attributes('aria-hidden')).toBe('true')
    await vi.advanceTimersByTimeAsync(2600)
    expect(w.find('.toast').exists()).toBe(false)
    w.unmount()
  })
  it('错误持续显示，可用明确关闭按钮关闭', async () => {
    const w = mount(ToastHost)
    toast('保存失败，输入仍在', 'ri-error-warning-line')
    await vi.advanceTimersByTimeAsync(60000)
    expect(w.get('[role="alert"]').text()).toContain('保存失败')
    await w.get('button[aria-label="关闭这条操作提示"]').trigger('click')
    expect(w.find('.toast').exists()).toBe(false)
    w.unmount()
  })
  it('指针与焦点分别暂停，全部离开后只使用剩余时间', async () => {
    const id = toast('需要阅读的结果')
    await vi.advanceTimersByTimeAsync(1000)
    pauseToast(id, 'pointer', true)
    pauseToast(id, 'focus', true)
    pauseToast(id, 'pointer', false)
    await vi.advanceTimersByTimeAsync(10000)
    expect(useToastHost().items).toHaveLength(1)
    pauseToast(id, 'focus', false)
    await vi.advanceTimersByTimeAsync(1599)
    expect(useToastHost().items).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(useToastHost().items).toHaveLength(0)
  })
  it('显式错误语义无需依赖图标，清理取消所有定时器', () => {
    toast('错误', undefined, { tone: 'error' })
    toast('短提示')
    expect(useToastHost().items[0]?.tone).toBe('error')
    clearToasts()
    expect(useToastHost().items).toHaveLength(0)
    expect(vi.getTimerCount()).toBe(0)
  })
})
