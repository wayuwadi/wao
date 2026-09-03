import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import CalendarView from '@/views/CalendarView.vue'
import { fetchAlternateWorkWeek, saveAlternateWorkWeek } from '@/api/workSchedule'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import {
  fetchHolidayEvents,
  fetchHolidaySettings,
  saveHolidayReminder,
  saveHolidaySettings,
  type HolidayCalendar,
  type HolidayEvent,
  type HolidaySettings,
} from '@/api/holiday'
import type { Task } from '@/types'
import type { AlternateWorkWeekSchedule } from '@/utils/workSchedule'
import {
  createFamilyImportantDate,
  convertCalendarDate,
  deleteFamilyImportantDate,
  fetchFamilyImportantDates,
  updateFamilyImportantDate,
  type FamilyImportantDate,
  type FamilyImportantDateCalendar,
  type SaveFamilyImportantDate,
} from '@/api/importantDates'
import { fetchFeishuChats } from '@/api/system'
import { fetchAttendance, syncAttendance } from '@/api/attendance'

vi.mock('@/api/caldav', () => ({
  fetchCaldavAccounts: vi.fn<() => Promise<never[]>>(async () => []),
  fetchCaldavEvents: vi.fn<() => Promise<never[]>>(async () => []),
}))

vi.mock('@/api/workSchedule', () => ({
  fetchAlternateWorkWeek: vi.fn<(today: string) => Promise<AlternateWorkWeekSchedule>>(async (today) => ({ enabled: false, anchorWeek: today, anchorType: 'small' })),
  saveAlternateWorkWeek: vi.fn<(value: AlternateWorkWeekSchedule) => Promise<AlternateWorkWeekSchedule>>(async (value) => value),
}))

vi.mock('@/api/holiday', () => ({
  fetchHolidayEvents: vi.fn<() => Promise<HolidayCalendar>>(),
  fetchHolidaySettings: vi.fn<() => Promise<HolidaySettings>>(),
  saveHolidaySettings: vi.fn<(selected: string[]) => Promise<HolidaySettings>>(),
  saveHolidayReminder: vi.fn<(code: string, value: { enabled: boolean; reminderTime: string | null }) => Promise<{
    holidayCode: string; enabled: boolean; reminderTime: string | null
  }>>(),
}))

vi.mock('@/api/importantDates', () => ({
  fetchFamilyImportantDates: vi.fn<() => Promise<FamilyImportantDateCalendar>>(),
  createFamilyImportantDate: vi.fn<(value: SaveFamilyImportantDate) => Promise<FamilyImportantDate>>(),
  updateFamilyImportantDate: vi.fn<(id: string, value: SaveFamilyImportantDate) => Promise<FamilyImportantDate>>(),
  deleteFamilyImportantDate: vi.fn<(id: string) => Promise<void>>(),
  convertCalendarDate: vi.fn(),
}))

vi.mock('@/api/system', () => ({
  fetchFeishuChats: vi.fn(async () => [{ chatId: 'oc_family', name: '家人群' }]),
}))

vi.mock('@/api/attendance', () => ({
  fetchAttendance: vi.fn(),
  syncAttendance: vi.fn(),
  saveAttendanceConfig: vi.fn(),
  saveAttendanceDay: vi.fn(),
  clearAttendanceDay: vi.fn(),
}))

const emptyHolidayCalendar: HolidayCalendar = { events: [], years: [] }
const defaultHolidaySettings: HolidaySettings = {
  selectedInternationalCategories: [],
  availableInternationalCategories: [
    { code: 'FAMILY', name: '亲情关怀', description: '母亲节、父亲节' },
    { code: 'WESTERN_POPULAR', name: '国际流行', description: '情人节、愚人节、万圣节' },
  ],
  reminderChannelAvailable: true,
  reminderChannelHint: '提醒将私聊飞书',
}
const emptyImportantDates: FamilyImportantDateCalendar = {
  items: [], events: [], reminderChannelAvailable: true, reminderChannelHint: '提醒将私聊飞书',
  groupChannelAvailable: true, groupChannelHint: '提醒将发到所选群并 @ 我',
}

function holiday(patch: Partial<HolidayEvent>): HolidayEvent {
  return {
    id: 'h1', code: 'china.spring-festival', date: '2026-08-14', year: 2026,
    name: '春节', category: 'CHINA', dayType: 'DAY_OFF', internationalCategory: null,
    sourceName: '2026年国务院办公厅节假日安排', sourceUrl: 'https://www.gov.cn/',
    remindable: true, reminderEnabled: false, reminderTime: null,
    ...patch,
  }
}

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
    vi.mocked(fetchHolidayEvents).mockReset().mockResolvedValue(emptyHolidayCalendar)
    vi.mocked(fetchHolidaySettings).mockReset().mockResolvedValue(defaultHolidaySettings)
    vi.mocked(saveHolidaySettings).mockReset().mockImplementation(async (selected) => ({
      ...defaultHolidaySettings, selectedInternationalCategories: selected,
    }))
    vi.mocked(saveHolidayReminder).mockReset().mockImplementation(async (holidayCode, value) => ({
      holidayCode, enabled: value.enabled, reminderTime: value.reminderTime,
    }))
    vi.mocked(fetchFamilyImportantDates).mockReset().mockResolvedValue(emptyImportantDates)
    vi.mocked(createFamilyImportantDate).mockReset()
    vi.mocked(updateFamilyImportantDate).mockReset()
    vi.mocked(deleteFamilyImportantDate).mockReset().mockResolvedValue()
    vi.mocked(fetchFeishuChats).mockReset().mockResolvedValue([{ chatId: 'oc_family', name: '家人群' }])
    vi.mocked(convertCalendarDate).mockReset().mockImplementation(async (value) => ({
      solarDate: value.solarDate ?? '2026-02-17',
      lunarYear: value.lunarYear ?? 2026,
      lunarMonth: value.lunarMonth ?? 1,
      lunarDay: value.lunarDay ?? 1,
      lunarLeapMonth: Boolean(value.lunarLeapMonth),
      solarLabel: value.solarDate ?? '2026-02-17',
      lunarLabel: '农历二〇二六年正月初一',
    }))
    vi.mocked(fetchAttendance).mockReset()
    vi.mocked(syncAttendance).mockReset()
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

    expect(wrapper.find('.create-choice-modal').exists()).toBe(true)
    await wrapper.find('.create-choice-grid button').trigger('click')
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

    await wrapper.find('.completed-toggle').trigger('click')
    expect(wrapper.findAll('.event.completed')).toHaveLength(0)
    expect(wrapper.find('.completed-toggle').text()).toContain('显示已完成任务')
    await wrapper.find('.completed-toggle').trigger('click')

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
    vi.mocked(fetchHolidayEvents).mockResolvedValue({ events: [
      holiday({ id: 'h1', date: data.today }),
      holiday({ id: 'h2', code: 'international.mothers-day', date: data.today, name: '母亲节',
        category: 'INTERNATIONAL', dayType: 'FESTIVAL', internationalCategory: 'FAMILY',
        sourceName: 'WAO 稳定公历规则', sourceUrl: null }),
      holiday({ id: 'h3', date: data.today, name: '春节调休上班', dayType: 'ADJUSTED_WORKDAY', remindable: false }),
    ], years: [{ year: 2026, officialScheduleAvailable: true,
      sourceName: '2026年国务院办公厅节假日安排', sourceUrl: 'https://www.gov.cn/',
      message: '该年度法定放假与调休安排已收录' }] })

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
      .mockResolvedValueOnce(emptyHolidayCalendar)

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()
    const retry = wrapper.find('button.holiday-status.error')
    expect(retry.text()).toContain('节假日加载失败')

    await retry.trigger('click')
    await flushPromises()
    expect(wrapper.find('button.holiday-status.error').exists()).toBe(false)
    expect(fetchHolidayEvents).toHaveBeenCalledTimes(2)
  })

  it('国际节日默认不添加，用户可主动订阅类别并从节日详情创建和关闭提醒', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    const mothersDay = holiday({
      id: 'mother', code: 'international.mothers-day', date: data.today, name: '母亲节',
      category: 'INTERNATIONAL', dayType: 'FESTIVAL', internationalCategory: 'FAMILY',
      sourceName: 'WAO 稳定公历规则', sourceUrl: null,
    })
    vi.mocked(fetchHolidayEvents)
      .mockResolvedValueOnce(emptyHolidayCalendar)
      .mockResolvedValueOnce({ events: [mothersDay], years: [] })

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('母亲节')
    expect(wrapper.find('.holiday-settings-entry').text()).toContain('0 类')

    await wrapper.find('.holiday-settings-entry').trigger('click')
    const family = wrapper.find('.holiday-category input[value="FAMILY"]')
    await family.setValue(true)
    await wrapper.find('.holiday-modal form').trigger('submit')
    await flushPromises()

    expect(saveHolidaySettings).toHaveBeenCalledWith(['FAMILY'])
    expect(wrapper.text()).toContain('母亲节')
    await wrapper.findAll('.event').find((event) => event.text().includes('母亲节'))!.trigger('click')
    expect(wrapper.find('.holiday-detail-modal').exists()).toBe(true)
    expect(wrapper.text()).toContain('2026 年数据')

    await wrapper.find('.holiday-reminder-form input[type="checkbox"]').setValue(true)
    await wrapper.find('.holiday-reminder-form input[type="time"]').setValue('08:30')
    await wrapper.find('.holiday-reminder-form').trigger('submit')
    await flushPromises()
    expect(saveHolidayReminder).toHaveBeenCalledWith('international.mothers-day', {
      enabled: true, reminderTime: '08:30',
    })

    const updated = { ...mothersDay, reminderEnabled: true, reminderTime: '08:30' }
    vi.mocked(fetchHolidayEvents).mockResolvedValue({ events: [updated], years: [] })
    await wrapper.findAll('.event').find((event) => event.text().includes('母亲节'))!.trigger('click')
    await wrapper.find('.holiday-reminder-form input[type="checkbox"]').setValue(false)
    await wrapper.find('.holiday-reminder-form').trigger('submit')
    await flushPromises()
    expect(saveHolidayReminder).toHaveBeenLastCalledWith('international.mothers-day', {
      enabled: false, reminderTime: null,
    })
  })

  it('可维护每年显示的家庭重要日，并设置提前飞书提醒', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    const items: FamilyImportantDate[] = []
    vi.mocked(fetchFamilyImportantDates).mockImplementation(async () => ({
      ...emptyImportantDates,
      items: [...items],
      events: items.map((item) => ({
        id: `event-${item.id}`, importantDateId: item.id, date: data.today, name: item.name,
        note: item.note, reminderEnabled: item.reminderEnabled,
        reminderDaysBefore: item.reminderDaysBefore, reminderTime: item.reminderTime,
        calendarType: item.calendarType, solarDateLabel: data.today,
        lunarDateLabel: item.lunarDateLabel, notificationType: item.notificationType,
        chatName: item.chatName,
      })),
    }))
    vi.mocked(createFamilyImportantDate).mockImplementation(async (value) => {
      const saved = { id: 'fid1', ...value, solarDateLabel: value.eventDate,
        lunarDateLabel: '农历二〇二六年七月初二' }
      items.push(saved)
      return saved
    })
    vi.mocked(updateFamilyImportantDate).mockImplementation(async (id, value) => {
      const saved = { id, ...value, solarDateLabel: value.eventDate,
        lunarDateLabel: '农历二〇二六年七月初二' }
      items.splice(0, 1, saved)
      return saved
    })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(deleteFamilyImportantDate).mockImplementation(async () => { items.splice(0) })

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()
    await wrapper.find('.important-date-entry').trigger('click')
    expect(wrapper.find('.important-date-empty').text()).toContain('还没有提醒事项')

    await wrapper.find('.important-date-empty button').trigger('click')
    await flushPromises()
    await wrapper.find('.important-date-field input[type="text"]').setValue('妈妈生日')
    await wrapper.find('.important-date-field input[type="date"]').setValue(data.today)
    await wrapper.find('.important-date-field textarea').setValue('记得提前订花')
    await wrapper.find('.important-date-reminder-toggle input').setValue(true)
    await wrapper.find('.important-date-field input[type="number"]').setValue(3)
    await wrapper.find('.important-date-field input[type="time"]').setValue('08:30')
    await wrapper.find('.important-date-form').trigger('submit')
    await flushPromises()

    expect(createFamilyImportantDate).toHaveBeenCalledWith({
      name: '妈妈生日', eventDate: data.today, note: '记得提前订花', reminderEnabled: true,
      reminderDaysBefore: 3, reminderTime: '08:30',
      calendarType: 'SOLAR', lunarMonth: 1, lunarDay: 1, lunarLeapMonth: false,
      notificationType: 'DIRECT', chatId: null, chatName: null,
    })
    expect(wrapper.find('.important-date-item').text()).toContain('提前 3 天 08:30 飞书私聊提醒')
    expect(wrapper.findAll('.event').some((event) => event.text().includes('妈妈生日'))).toBe(true)

    await wrapper.find('.important-date-main').trigger('click')
    await wrapper.find('.important-date-field input[type="text"]').setValue('妈妈生日纪念')
    await wrapper.find('.important-date-form').trigger('submit')
    await flushPromises()
    expect(updateFamilyImportantDate).toHaveBeenCalledWith('fid1', expect.objectContaining({ name: '妈妈生日纪念' }))

    await wrapper.find('.important-date-delete').trigger('click')
    await flushPromises()
    expect(deleteFamilyImportantDate).toHaveBeenCalledWith('fid1')
    expect(wrapper.find('.important-date-empty').exists()).toBe(true)
  })

  it('从日历可分流创建农历群提醒，并可搜索后跳到当日预览', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    const reminder: FamilyImportantDate = {
      id: 'fid-lunar', name: '农历生日', eventDate: data.today, note: null,
      reminderEnabled: true, reminderDaysBefore: 1, reminderTime: '09:00',
      calendarType: 'LUNAR', lunarMonth: 7, lunarDay: 2, lunarLeapMonth: false,
      solarDateLabel: data.today, lunarDateLabel: '农历二〇二六年七月初二',
      notificationType: 'GROUP', chatId: 'oc_family', chatName: '家人群',
    }
    vi.mocked(fetchFamilyImportantDates).mockResolvedValue({
      ...emptyImportantDates, items: [reminder], events: [{
        id: 'event-lunar', importantDateId: reminder.id, date: data.today, name: reminder.name,
        note: null, reminderEnabled: true, reminderDaysBefore: 1, reminderTime: '09:00',
        calendarType: 'LUNAR', solarDateLabel: data.today,
        lunarDateLabel: reminder.lunarDateLabel, notificationType: 'GROUP', chatName: '家人群',
      }],
    })

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()

    await wrapper.find('.day-num').trigger('dblclick')
    expect(wrapper.find('.create-choice-modal').text()).toContain('任务')
    expect(wrapper.find('.create-choice-modal').text()).toContain('提醒事项')
    await wrapper.findAll('.create-choice-grid button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.important-date-form').exists()).toBe(true)
    await wrapper.findAll('.calendar-type-switch button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.date-conversion').text()).toContain('对应公历')
    await wrapper.findAll('.notification-type button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.find('.important-date-field select option').exists()).toBe(true)

    await wrapper.find('.cd-close').trigger('click')
    await wrapper.find('.calendar-search input').setValue('农历生日')
    expect(wrapper.find('.calendar-search-results').text()).toContain('农历生日')
    await wrapper.find('.calendar-search-results button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.important-date-modal').exists()).toBe(true)
    expect(wrapper.find('.important-date-form input[type="text"]').element).toHaveProperty('value', '农历生日')
  })

  it('考勤模式可独立开关，并在日历显示打卡与两位小数工时', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    const attendance = {
      from: data.today, to: data.today,
      config: { enabled: true, username: 's008500@nl-ds.cn', vaultItemId: 'vi1', status: 'OK', lastSyncedAt: `${data.today}T09:05:00`, lastError: null },
      days: [{ date: data.today, clockInAt: `${data.today}T09:00:00`, clockOutAt: `${data.today}T21:00:00`, dayType: 'WORKDAY', anomaly: null, manualOverride: false, workHours: 11, expectedWorkday: true, workWeekLabel: '大周 · 周日上班' }],
      totalHours: 11, monthDailyAverageHours: 11, validDays: 1,
      weeks: [{ from: data.today, to: data.today, label: '大周 · 周日上班', averageHours: 11, validDays: 1 }],
    } as const
    vi.mocked(fetchAttendance).mockResolvedValue(attendance as never)

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('11.00 h')
    await wrapper.find('.attendance-toggle').trigger('click')
    await flushPromises()
    expect(fetchAttendance).toHaveBeenCalled()
    expect(wrapper.text()).toContain('11.00 h')
    expect(wrapper.findAll('.event').some((event) => event.text().includes('考勤'))).toBe(true)
    await wrapper.find('.attendance-toggle').trigger('click')
    expect(wrapper.find('.attendance-panel').exists()).toBe(false)
  })

  it('首次配置考勤时自动启用表单并忽略旧的禁用草稿', async () => {
    const data = useDataStore()
    const session = useSessionStore()
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    localStorage.setItem('wao-attendance-config-draft:wg', JSON.stringify({
      enabled: false, username: '', vaultItemId: '',
    }))
    vi.mocked(fetchAttendance).mockResolvedValue({
      from: data.today, to: data.today,
      config: { enabled: false, username: null, vaultItemId: null, status: 'UNCONFIGURED', lastSyncedAt: null, lastError: null },
      days: [], totalHours: 0, monthDailyAverageHours: 0, validDays: 0, weeks: [],
    })

    const wrapper = mount(CalendarView, { global: { plugins: [router] } })
    await flushPromises()
    await wrapper.find('.attendance-toggle').trigger('click')
    await flushPromises()
    await wrapper.findAll('.attendance-actions button').find((button) => button.text().includes('设置'))!.trigger('click')

    expect((wrapper.find('.attendance-modal .toggle input').element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.find('.attendance-modal fieldset').attributes('disabled')).toBeUndefined()
    expect((wrapper.find('.attendance-modal input[type="email"]').element as HTMLInputElement).disabled).toBe(false)
    expect((wrapper.find('.attendance-modal select').element as HTMLSelectElement).disabled).toBe(false)

    data.vault = [{
      id: 'vg1', group: '工作', items: [{
        id: 'vi1', name: '摩卡-工时登录系统', icon: 'ri-key-line', user: 's008500@nl-ds.cn',
        site: 'https://core.mokahr.com', note: '', updated: '刚刚', hasSecret: true,
      }],
    }]
    await flushPromises()
    await wrapper.find('.attendance-modal select').setValue('vi1')

    const email = wrapper.find('.attendance-modal input[type="email"]')
    expect(email.attributes('readonly')).toBeDefined()
    expect((email.element as HTMLInputElement).value).toBe('s008500@nl-ds.cn')
  })
})
