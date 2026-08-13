import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import CalendarView from '@/views/CalendarView.vue'
import { fetchAlternateWorkWeek, saveAlternateWorkWeek } from '@/api/workSchedule'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { fetchHolidayEvents } from '@/api/holiday'
import type { Task } from '@/types'
import type { AlternateWorkWeekSchedule } from '@/utils/workSchedule'

vi.mock('@/api/caldav', () => ({
  fetchCaldavAccounts: vi.fn<() => Promise<never[]>>(async () => []),
  fetchCaldavEvents: vi.fn<() => Promise<never[]>>(async () => []),
}))

vi.mock('@/api/workSchedule', () => ({
  fetchAlternateWorkWeek: vi.fn<(today: string) => Promise<AlternateWorkWeekSchedule>>(async (today) => ({ enabled: false, anchorWeek: today, anchorType: 'small' })),
  saveAlternateWorkWeek: vi.fn<(value: AlternateWorkWeekSchedule) => Promise<AlternateWorkWeekSchedule>>(async (value) => value),
}))

vi.mock('@/api/holiday', () => ({
  fetchHolidayEvents: vi.fn<() => Promise<never[]>>(async () => []),
}))

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
})

describe('日历成员隔离、清单与时间轴', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(fetchAlternateWorkWeek).mockImplementation(async (today) => ({ enabled: false, anchorWeek: today, anchorType: 'small' }))
    vi.mocked(saveAlternateWorkWeek).mockImplementation(async (value) => value)
    vi.mocked(fetchHolidayEvents).mockReset().mockResolvedValue([])
  })

  it('默认只看当前人，待安排清单只含未排期与已逾期任务并可集中筛选', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    const ui = useUiStore()
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    const other = { id: 'wd', name: '娃弟', initial: '弟', color: '#2C8A5B', role: '成员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me, other]
    const base = data.tasks[0]!
    const task = (patch: Partial<Task>): Task => ({
      ...base,
      id: crypto.randomUUID(),
      parts: [], steps: [], subs: [], files: [], docs: [], linkTasks: [],
      comments: [], custom: [], activity: [], images: [],
      ...patch,
    })
    data.tasks = [
      task({ title: '我的已排期 P2', owner: session.currentUser.id, pr: 'P2', due: data.today }),
      task({ title: '我的未排期 P0', owner: session.currentUser.id, pr: 'P0', due: '' }),
      task({ title: '我的逾期 P1', owner: session.currentUser.id, pr: 'P1', due: '2026-07-01' }),
      task({ title: '他人的任务', owner: other.id, pr: 'P0', due: data.today }),
    ]

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()

    const backlog = wrapper.findAll('.bl-item')
    expect(backlog).toHaveLength(2)
    expect(backlog[0]!.text()).toContain('我的未排期 P0')
    expect(backlog[1]!.text()).toContain('我的逾期 P1')
    expect(wrapper.text()).toContain('我的已排期 P2')
    expect(wrapper.text()).not.toContain('他人的任务')

    await wrapper.find('.bl-filter select').setValue('overdue')
    expect(wrapper.findAll('.bl-item')).toHaveLength(1)
    expect(wrapper.find('.bl-item').text()).toContain('我的逾期 P1')

    await wrapper.find('.view-switch button:last-child').trigger('click')
    const slots = wrapper.findAll('.time-day .time-slot')
    expect(slots).toHaveLength(24)
    await slots[9]!.trigger('mousedown')
    await slots[10]!.trigger('mouseenter')
    await wrapper.find('.timeline').trigger('mouseup')

    expect(ui.modal).toBe('task')
    expect(ui.modalStartAt).toBe(`${data.today}T09:00`)
    expect(ui.modalEndAt).toBe(`${data.today}T11:00`)
  })

  it('已完成任务保留在原日期，月视图和时间轴均以删除线显示', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    const base = data.tasks[0]!
    data.tasks = [{
      ...base,
      id: 'done-calendar-task',
      title: '已完成的时间段任务',
      owner: me.id,
      status: '已完成',
      due: data.today,
      startAt: `${data.today}T09:00`,
      endAt: `${data.today}T10:00`,
      parts: [], steps: [], subs: [], files: [], docs: [], linkTasks: [],
      comments: [], custom: [], activity: [], images: [],
    }, {
      ...base,
      id: 'done-all-day-task',
      title: '已完成的截止任务',
      owner: me.id,
      status: '已完成',
      due: data.today,
      startAt: null,
      endAt: null,
      parts: [], steps: [], subs: [], files: [], docs: [], linkTasks: [],
      comments: [], custom: [], activity: [], images: [],
    }]

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()

    const monthEvents = wrapper.findAll('.event.completed')
    expect(monthEvents).toHaveLength(2)
    expect(monthEvents.map((event) => event.text())).toEqual(expect.arrayContaining([
      expect.stringContaining('已完成的时间段任务'),
      expect.stringContaining('已完成的截止任务'),
    ]))
    expect(wrapper.findAll('.bl-item')).toHaveLength(0)

    await wrapper.findAll('.view-switch button')[3]!.trigger('click')
    const timelineEvent = wrapper.find('.timeline-event.completed')
    expect(timelineEvent.exists()).toBe(true)
    expect(timelineEvent.text()).toContain('已完成的时间段任务')
    const allDayEvent = wrapper.find('.all-day-strip button.completed')
    expect(allDayEvent.exists()).toBe(true)
    expect(allDayEvent.text()).toContain('已完成的截止任务')
  })

  it('保存本周双休基准后，月视图可查询交替的大小周并持久化成员偏好', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    vi.mocked(fetchAlternateWorkWeek).mockResolvedValue({
      enabled: true,
      anchorWeek: data.today,
      anchorType: 'small',
    })

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('.work-schedule').text()).toContain('本周 · 小周 · 双休')
    const marks = wrapper.findAll('.work-week-mark').map((mark) => mark.text())
    expect(marks).toContain('小周 · 双休')
    expect(marks).toContain('大周 · 周日上班')

    await wrapper.find('.work-schedule').trigger('click')
    expect(wrapper.find('.work-schedule-modal').exists()).toBe(true)
    await wrapper.find('.work-schedule-field input').setValue(data.today)
    await wrapper.find('.work-schedule-field select').setValue('small')
    await wrapper.find('.work-schedule-modal form').trigger('submit')
    await flushPromises()

    expect(saveAlternateWorkWeek).toHaveBeenCalledWith({
      enabled: true,
      anchorWeek: data.today,
      anchorType: 'small',
    })
    expect(wrapper.find('.work-schedule-modal').exists()).toBe(false)
  })

  it('大小周保存失败时保留浮层与草稿，并提供原位重试', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    vi.mocked(saveAlternateWorkWeek).mockRejectedValueOnce(new Error('网络暂不可用'))

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()
    await wrapper.find('.work-schedule').trigger('click')
    await wrapper.find('.work-schedule-toggle input').setValue(true)
    await wrapper.find('.work-schedule-field input').setValue(data.today)
    await wrapper.find('.work-schedule-modal form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.work-schedule-modal').exists()).toBe(true)
    expect(wrapper.find('.work-schedule-state.error').text()).toContain('网络暂不可用')
    expect(wrapper.find('.work-schedule-field input').element).toHaveProperty('value', data.today)
    expect(wrapper.find('.work-schedule-save').text()).toContain('重试保存')
  })

  it('合并展示中国与国际节日，并与大小周共同标记法定休息日和调休上班日', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    vi.mocked(fetchAlternateWorkWeek).mockResolvedValue({
      enabled: true,
      anchorWeek: data.today,
      anchorType: 'small',
    })
    vi.mocked(fetchHolidayEvents).mockResolvedValue([
      { id: 'h1', date: data.today, name: '春节', category: 'CHINA', dayType: 'DAY_OFF' },
      { id: 'h2', date: data.today, name: '母亲节', category: 'INTERNATIONAL', dayType: 'FESTIVAL' },
      { id: 'h3', date: data.today, name: '春节调休上班', category: 'CHINA', dayType: 'ADJUSTED_WORKDAY' },
    ])

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('中国节假日')
    expect(wrapper.text()).toContain('国际节日')
    expect(wrapper.text()).toContain('母亲节')
    expect(wrapper.text()).toContain('春节调休上班')
    expect(wrapper.find('.day-mark.work').text()).toBe('班')
    expect(wrapper.findAll('.work-week-mark').length).toBeGreaterThan(0)
  })

  it('节假日加载失败时给出可用的重试入口', async () => {
    const session = useSessionStore()
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    vi.mocked(fetchHolidayEvents)
      .mockRejectedValueOnce(new Error('网络失败'))
      .mockResolvedValueOnce([])

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()
    const retry = wrapper.find('button.holiday-status.error')
    expect(retry.text()).toContain('节假日加载失败')

    await retry.trigger('click')
    await flushPromises()
    expect(wrapper.find('button.holiday-status.error').exists()).toBe(false)
    expect(fetchHolidayEvents).toHaveBeenCalledTimes(2)
  })
})
