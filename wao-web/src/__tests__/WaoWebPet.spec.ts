import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WaoWebPet from '@/components/assistant/WaoWebPet.vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import type { Task, TaskStatus } from '@/types'
import {
  cloneRhythmConfig,
  defaultRhythmDay,
  DEFAULT_WORKDAY_RHYTHM,
  shanghaiClock,
  type WorkdayRhythmConfig,
  type WorkdayRhythmDay,
} from '@/utils/workdayRhythm'

const rhythmApi = vi.hoisted(() => ({
  fetchWorkdayRhythm: vi.fn<() => Promise<WorkdayRhythmConfig>>(),
  fetchWorkdayRhythmDay:
    vi.fn<
      (
        date: string,
        weekday: number,
        blockIds: string[],
        energyIds: string[],
      ) => Promise<WorkdayRhythmDay>
    >(),
  saveWorkdayRhythmDay: vi.fn<(day: WorkdayRhythmDay) => Promise<void>>(),
  sendEnergyNotification:
    vi.fn<(action: string, date: string) => Promise<{ sent: boolean; chatName: string }>>(),
}))

vi.mock('@/api/workdayRhythm', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/workdayRhythm')>()),
  ...rhythmApi,
}))

function task(id: string, title: string, status: TaskStatus, pr: Task['pr'] = 'P2'): Task {
  return {
    id,
    title,
    status,
    pr,
    project: 'p1',
    owner: 'wg',
    parts: [],
    due: '2026-08-27',
    desc: '',
    steps: [],
    subs: [],
    files: [],
    docs: [],
    linkTasks: [],
    comments: [],
    custom: [],
    activity: [],
  }
}

function touchPointerEvent(
  type: string,
  {
    pointerId,
    clientX = 0,
    clientY = 0,
  }: { pointerId: number; clientX?: number; clientY?: number },
) {
  const event = new MouseEvent(type, { bubbles: true, button: 0, clientX, clientY })
  Object.defineProperties(event, {
    pointerId: { value: pointerId },
    pointerType: { value: 'touch' },
  })
  return event
}

describe('WaoWebPet', () => {
  it('拖动才播放方向走路帧，持续移动不重置，暂停和释放恢复且吞掉拖后点击', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('Image', class { onload?: () => void; set src(_value: string) { this.onload?.() } })
    vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    const wrapper = mount(WaoWebPet, { props: { assistantStatus: { configured: true, message: '已连接' } } })
    const avatar = wrapper.get('.pet-avatar')
    Object.defineProperties(avatar.element, {
      setPointerCapture: { value: vi.fn() }, hasPointerCapture: { value: () => true }, releasePointerCapture: { value: vi.fn() },
    })
    const pointer = (type: string, x: number) => touchPointerEvent(type, { pointerId: 77, clientX: x, clientY: 80 })
    await vi.advanceTimersByTimeAsync(1)
    avatar.element.dispatchEvent(pointer('pointerdown', 120))
    await nextTick()
    expect(wrapper.get('.wao-web-pet').classes()).toContain('dragging')
    expect(wrapper.get('.pet-avatar .pet-sprite').attributes('data-state')).toBe('idle')
    window.dispatchEvent(pointer('pointermove', 150))
    await nextTick()
    expect(wrapper.get('.pet-avatar .pet-sprite').attributes('data-state')).toBe('running-right')
    await vi.advanceTimersByTimeAsync(100)
    window.dispatchEvent(pointer('pointermove', 170))
    await vi.advanceTimersByTimeAsync(40)
    expect(wrapper.get('.pet-avatar .pet-sprite').attributes('data-frame')).toBe('1')
    window.dispatchEvent(pointer('pointermove', 140))
    await nextTick()
    expect(wrapper.get('.pet-avatar .pet-sprite').attributes('data-state')).toBe('running-left')
    await vi.advanceTimersByTimeAsync(190)
    expect(wrapper.get('.pet-avatar .pet-sprite').attributes('data-state')).toBe('idle')
    window.dispatchEvent(pointer('pointerup', 140))
    await nextTick()
    await avatar.trigger('click')
    expect(useUiStore().chatOpen).toBe(false)
    expect(localStorage.getItem('wao-assistant-fab-position-v1')).not.toBeNull()
    wrapper.unmount()
  })
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    sessionStorage.clear()
    const clock = shanghaiClock()
    rhythmApi.fetchWorkdayRhythm.mockReset()
    rhythmApi.fetchWorkdayRhythmDay.mockReset()
    rhythmApi.saveWorkdayRhythmDay.mockReset()
    rhythmApi.sendEnergyNotification.mockReset()
    rhythmApi.fetchWorkdayRhythm.mockResolvedValue(cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM))
    rhythmApi.fetchWorkdayRhythmDay.mockResolvedValue(defaultRhythmDay(clock.date, clock.weekday))
    rhythmApi.saveWorkdayRhythmDay.mockResolvedValue(undefined)
    rhythmApi.sendEnergyNotification.mockResolvedValue({ sent: true, chatName: 'WAO 群' })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('只保留宠物本体，点击聊天且悬停不再展开按钮', async () => {
    const wrapper = mount(WaoWebPet, { props: { assistantStatus: { configured: true, message: '' } } })
    await wrapper.get('.pet-avatar').trigger('pointerenter')
    expect(wrapper.find('.pet-quick-actions').exists()).toBe(false)
    expect(wrapper.find('.pet-silence-close').exists()).toBe(false)
    expect(wrapper.find('.pet-badge').exists()).toBe(false)
    await wrapper.get('.pet-avatar').trigger('click')
    expect(useUiStore().chatOpen).toBe(true)
    wrapper.unmount()
  })
})
