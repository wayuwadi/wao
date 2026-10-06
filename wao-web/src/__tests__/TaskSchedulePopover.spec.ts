import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TaskSchedulePopover from '@/components/todo/TaskSchedulePopover.vue'
import { useDataStore } from '@/stores/data'
import { seedDataStore } from './helpers/seedDataStore'

describe('时间安排在视口变化时保留编辑', () => {
  let wrapper: VueWrapper
  let viewport: EventTarget & { width: number; height: number; offsetLeft: number; offsetTop: number }
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    seedDataStore()
    viewport = Object.assign(new EventTarget(), { width: 390, height: 844, offsetLeft: 0, offsetTop: 0 })
    vi.stubGlobal('visualViewport', viewport)
    wrapper = mount(TaskSchedulePopover, { attachTo: document.body, props: { task: useDataStore().tasks[0]!, day: '2026-10-07' } })
  })
  afterEach(() => { wrapper.unmount(); document.body.innerHTML = ''; vi.unstubAllGlobals() })
  const panel = () => document.querySelector<HTMLElement>('[role="dialog"]')!
  async function openExact() {
    await wrapper.get('.time-chip').trigger('click')
    ;(panel().querySelector('.secondary') as HTMLButtonElement).click()
    await flushPromises()
  }
  it('窗口收缩不关闭精确输入，焦点和草稿保留', async () => {
    await openExact()
    const input = panel().querySelector<HTMLInputElement>('[aria-label="开始时间"]')!
    input.value = '2026-10-07T11:15'; input.dispatchEvent(new Event('input')); input.focus()
    viewport.height = 430
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    expect(panel()).not.toBeNull()
    expect(input.value).toBe('2026-10-07T11:15')
    expect(document.activeElement).toBe(input)
    expect(panel().style.maxHeight).toBe('406px')
    const key = Object.keys(localStorage).find(key => key.startsWith('wao-schedule-draft-v1:'))!
    expect(JSON.parse(localStorage.getItem(key)!).starts).toBe('2026-10-07T11:15')
    await wrapper.get('.time-chip').trigger('click')
    await wrapper.get('.time-chip').trigger('click')
    expect(panel().querySelector<HTMLInputElement>('[aria-label="开始时间"]')!.value).toBe('2026-10-07T11:15')
  })
  it('visualViewport 缩小和移动后在可用区域重排，卸载清理监听', async () => {
    await openExact()
    const remove = vi.spyOn(viewport, 'removeEventListener')
    viewport.width = 320; viewport.height = 300; viewport.offsetLeft = 5; viewport.offsetTop = 120
    viewport.dispatchEvent(new Event('resize')); viewport.dispatchEvent(new Event('scroll'))
    await flushPromises()
    expect(panel().style.width).toBe('296px')
    expect(panel().style.left).toBe('17px')
    expect(Number.parseFloat(panel().style.top)).toBeGreaterThanOrEqual(132)
    expect(panel().style.maxHeight).toBe('276px')
    wrapper.unmount()
    expect(remove.mock.calls.map(call => call[0])).toEqual(['resize', 'scroll'])
  })
  it('Esc 仍关闭并恢复焦点，重新打开保留草稿', async () => {
    await openExact()
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await flushPromises()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(document.activeElement).toBe(wrapper.get('.time-chip').element)
  })
})
