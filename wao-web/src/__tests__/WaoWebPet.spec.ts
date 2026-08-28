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

describe('WaoWebPet', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
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
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('优先展示真实待验收任务并可直接打开详情', async () => {
    const data = useDataStore()
    const ui = useUiStore()
    data.taskLoadState = 'loaded'
    data.tasks.push(
      task('todo', '整理下周计划', '待办'),
      task('running', '正在实现网页宠物', 'AI 进行中'),
      task('review', '验收登录体验', '待验证', 'P1'),
    )

    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
    })

    expect(wrapper.get('.pet-notice b').text()).toBe('AI 已完成 · 1 项等你验收')
    expect(wrapper.get('.pet-notice small').text()).toBe('验收登录体验')
    expect(wrapper.get('.pet-badge').text()).toBe('3')
    await wrapper.get('.pet-notice').trigger('click')
    expect(ui.openTaskId).toBe('review')
    wrapper.unmount()
  })

  it('真实指针序列由提醒按钮保持捕获并打开对应任务', async () => {
    const data = useDataStore()
    const ui = useUiStore()
    data.taskLoadState = 'loaded'
    data.tasks.push(task('review-pointer', '验收气泡点击', '待验证', 'P1'))

    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
    })
    const notice = wrapper.get('.pet-notice')
    const setPointerCapture = vi.fn<(pointerId: number) => void>()
    const releasePointerCapture = vi.fn<(pointerId: number) => void>()
    Object.defineProperties(notice.element, {
      setPointerCapture: { configurable: true, value: setPointerCapture },
      hasPointerCapture: { configurable: true, value: () => true },
      releasePointerCapture: { configurable: true, value: releasePointerCapture },
    })

    const pointerEvent = (type: string) => {
      const event = new MouseEvent(type, {
        bubbles: true,
        button: 0,
        clientX: 120,
        clientY: 60,
      })
      Object.defineProperty(event, 'pointerId', { value: 7 })
      return event
    }
    notice.element.dispatchEvent(pointerEvent('pointerdown'))
    await nextTick()
    expect(setPointerCapture).toHaveBeenCalledWith(7)

    window.dispatchEvent(pointerEvent('pointerup'))
    await nextTick()
    await notice.trigger('click')

    expect(releasePointerCapture).toHaveBeenCalledWith(7)
    expect(ui.openTaskId).toBe('review-pointer')
    wrapper.unmount()
  })

  it('按待验收、执行中、待办顺序轮播真实任务动态', async () => {
    vi.useFakeTimers()
    const data = useDataStore()
    data.taskLoadState = 'loaded'
    data.tasks.push(
      task('todo', '整理下周计划', '待办'),
      task('running', '正在实现网页宠物', 'AI 进行中'),
      task('review', '验收登录体验', '待验证'),
    )
    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
    })

    expect(wrapper.text()).toContain('AI 已完成')
    await vi.advanceTimersByTimeAsync(7_000)
    await nextTick()
    expect(wrapper.text()).toContain('AI 正在工作')
    await vi.advanceTimersByTimeAsync(7_000)
    await nextTick()
    expect(wrapper.text()).toContain('待办提醒')
    wrapper.unmount()
  })

  it('任务读取失败时明确显示失败，点击宠物只互动并展开真实快捷操作', async () => {
    const data = useDataStore()
    data.taskLoadState = 'error'
    data.taskLoadError = '任务接口暂时不可达'
    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: false, message: '本地 Codex 暂时离线' } },
    })

    expect(wrapper.text()).toContain('任务动态暂时不可用')
    expect(wrapper.text()).toContain('任务接口暂时不可达')
    await wrapper.get('.pet-avatar').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('open-assistant')).toBeUndefined()
    expect(wrapper.get('.pet-avatar').attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('.pet-rhythm-action')).toHaveLength(2)
    expect(wrapper.get('.pet-settings-link').attributes('type')).toBe('button')
    expect(wrapper.find('.pet-name').exists()).toBe(false)
    wrapper.unmount()
  })

  it('点击宠物设置通过站内路由直达真实配置入口', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        {
          path: '/settings',
          name: 'settings',
          component: { template: '<div />' },
        },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
      global: { plugins: [router] },
    })

    await wrapper.get('.pet-settings-link').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/settings?tab=pet')
    wrapper.unmount()
  })

  it('右键宠物打开功能气泡，并可临时隐藏为侧边静默图标', async () => {
    vi.useFakeTimers()
    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
    })

    await wrapper.get('.pet-avatar').trigger('contextmenu')
    expect(wrapper.get('.pet-settings-actions').classes()).toContain('open')
    const hideButtons = wrapper.findAll('.pet-menu-group')[1]!.findAll('button')
    await hideButtons[0]!.trigger('click')
    await nextTick()

    expect(wrapper.find('.wao-web-pet').exists()).toBe(false)
    expect(wrapper.get('.pet-silent-dock').attributes('title')).toContain('点击立即恢复')
    await wrapper.get('.pet-silent-dock').trigger('click')
    expect(wrapper.find('.wao-web-pet').exists()).toBe(true)
    wrapper.unmount()
  })

  it('任务气泡关闭按钮提供 5 分钟、10 分钟和自选静音', async () => {
    const data = useDataStore()
    data.taskLoadState = 'loaded'
    data.tasks.push(task('notice-snooze', '稍后处理气泡', '待办'))
    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
    })

    await wrapper.get('.pet-notice-close').trigger('click')
    expect(wrapper.get('.notice-snooze-menu').text()).toContain('5 分钟')
    expect(wrapper.get('.notice-snooze-menu').text()).toContain('10 分钟')
    expect(wrapper.get('.notice-snooze-menu').text()).toContain('自选 5 分钟')
    await wrapper.get('.notice-snooze-menu').findAll('button')[0]!.trigger('click')
    expect(wrapper.find('.pet-notice').exists()).toBe(false)
    wrapper.unmount()
  })

  it('悬停后展示前三个真实补给项目并把累计动作保存到当天记录', async () => {
    const clock = shanghaiClock()
    const config = cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM)
    config.energyItems = [
      { id: 'WATER', title: '喝水', guidance: '补充水分', mode: 'COUNT', notify: false },
      { id: 'EXERCISE', title: '运动', guidance: '活动一下', mode: 'COUNT', notify: false },
      {
        id: 'MEDITATION',
        title: '冥想',
        guidance: '停下来几分钟',
        mode: 'DAILY_ONCE',
        notify: false,
      },
      { id: 'WALK', title: '散步', guidance: '出去走走', mode: 'COUNT', notify: false },
    ]
    const day = defaultRhythmDay(clock.date, clock.weekday)
    day.energyRecords.EXERCISE = 1
    rhythmApi.fetchWorkdayRhythm.mockResolvedValue(config)
    rhythmApi.fetchWorkdayRhythmDay.mockResolvedValue(day)
    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
    })

    await wrapper.get('.pet-control').trigger('pointerenter')
    await flushPromises()

    const actions = wrapper.findAll('.pet-rhythm-action')
    expect(actions).toHaveLength(3)
    expect(actions.map((action) => action.get('b').text())).toEqual(['喝水', '运动', '冥想'])
    expect(wrapper.text()).not.toContain('散步')

    await actions[1]!.trigger('click')
    await flushPromises()

    expect(rhythmApi.saveWorkdayRhythmDay).toHaveBeenCalledTimes(1)
    expect(rhythmApi.saveWorkdayRhythmDay.mock.calls[0]![0].energyRecords.EXERCISE).toBe(2)
    expect(wrapper.findAll('.pet-rhythm-action')[1]!.text()).toContain('2')

    await wrapper.findAll('.pet-rhythm-action')[2]!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.pet-rhythm-action')[2]!.text()).toContain('已保存')
    expect(wrapper.findAll('.pet-rhythm-action')[2]!.attributes('disabled')).toBeDefined()
    expect(rhythmApi.saveWorkdayRhythmDay.mock.calls[1]![0].energyRecords.MEDITATION).toBe(1)
    wrapper.unmount()
  })

  it('键盘聚焦宠物后同样加载快捷动作并暂时收起任务提示', async () => {
    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
    })

    expect(wrapper.find('.pet-notice').exists()).toBe(true)
    await wrapper.get('.pet-avatar').trigger('focusin')
    await flushPromises()

    expect(wrapper.find('.pet-notice').exists()).toBe(false)
    expect(wrapper.findAll('.pet-rhythm-action')).toHaveLength(2)
    expect(rhythmApi.fetchWorkdayRhythm).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('保存失败后点击原动作只重试同一条记录，不重复累计', async () => {
    rhythmApi.saveWorkdayRhythmDay
      .mockRejectedValueOnce(new Error('偏好服务暂时不可用'))
      .mockResolvedValueOnce(undefined)
    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
    })

    await wrapper.get('.pet-control').trigger('pointerenter')
    await flushPromises()
    await wrapper.findAll('.pet-rhythm-action')[0]!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.pet-rhythm-action')[0]!.text()).toContain('保存失败 · 点击重试')

    await wrapper.findAll('.pet-rhythm-action')[0]!.trigger('click')
    await flushPromises()

    expect(rhythmApi.saveWorkdayRhythmDay).toHaveBeenCalledTimes(2)
    expect(rhythmApi.saveWorkdayRhythmDay.mock.calls[0]![0].energyRecords.WATER).toBe(1)
    expect(rhythmApi.saveWorkdayRhythmDay.mock.calls[1]![0].energyRecords.WATER).toBe(1)
    wrapper.unmount()
  })

  it('通知失败后重试只重新发送通知，不再次保存或增加次数', async () => {
    const config = cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM)
    config.energyNotification.enabled = true
    rhythmApi.fetchWorkdayRhythm.mockResolvedValue(config)
    rhythmApi.sendEnergyNotification
      .mockRejectedValueOnce(new Error('飞书暂时不可达'))
      .mockResolvedValueOnce({ sent: true, chatName: 'WAO 群' })
    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
    })

    await wrapper.get('.pet-control').trigger('pointerenter')
    await flushPromises()
    await wrapper.findAll('.pet-rhythm-action')[0]!.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.pet-rhythm-action')[0]!.text()).toContain('通知失败，点击重试')

    await wrapper.findAll('.pet-rhythm-action')[0]!.trigger('click')
    await flushPromises()

    expect(rhythmApi.saveWorkdayRhythmDay).toHaveBeenCalledTimes(1)
    expect(rhythmApi.sendEnergyNotification).toHaveBeenCalledTimes(2)
    expect(rhythmApi.saveWorkdayRhythmDay.mock.calls[0]![0].energyRecords.WATER).toBe(1)
    wrapper.unmount()
  })

  it('检测到今日节奏未保存草稿时禁止快捷写入并提供恢复入口', async () => {
    const clock = shanghaiClock()
    localStorage.setItem(`wao-workday-rhythm-day-draft-${clock.date}`, '{"draft":true}')
    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
    })

    await wrapper.get('.pet-control').trigger('pointerenter')
    await flushPromises()

    expect(wrapper.findAll('.pet-rhythm-action')).toHaveLength(0)
    const recovery = wrapper.get('.pet-rhythm-state.conflict')
    expect(recovery.text()).toContain('先保存页面草稿')
    expect(recovery.attributes('href')).toBe('/workday-rhythm')
    expect(rhythmApi.saveWorkdayRhythmDay).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('今日节奏读取失败时显示真实原因并可原位重试', async () => {
    rhythmApi.fetchWorkdayRhythm
      .mockRejectedValueOnce(new Error('成员偏好接口暂时不可达'))
      .mockResolvedValueOnce(cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM))
    const wrapper = mount(WaoWebPet, {
      props: { assistantStatus: { configured: true, message: '已连接本地 Codex' } },
    })

    await wrapper.get('.pet-control').trigger('pointerenter')
    await flushPromises()
    const retry = wrapper.get('.pet-rhythm-state.error')
    expect(retry.text()).toContain('成员偏好接口暂时不可达')

    await retry.trigger('click')
    await flushPromises()

    expect(rhythmApi.fetchWorkdayRhythm).toHaveBeenCalledTimes(2)
    expect(wrapper.findAll('.pet-rhythm-action')).toHaveLength(2)
    wrapper.unmount()
  })
})
