import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import CalendarView from '@/views/CalendarView.vue'
vi.mock('@/api/workdayRhythm', () => ({ fetchWorkdayRhythm: vi.fn(async () => ({ blocks: [] })) }))
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
import { fetchVault, apiPutTask } from '@/api/board'

vi.mock('@/api/board', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/api/board')>(),
  fetchVault: vi.fn(),
  apiPutTask: vi.fn(),
}))

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

let router: ReturnType<typeof createRouter>

enableAutoUnmount(afterEach)
let pinia: ReturnType<typeof createPinia>

describe('日历成员隔离、清单与时间轴', () => {
  beforeEach(() => {
    localStorage.clear()
    pinia = createPinia()
    setActivePinia(pinia)
    router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
    })
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
    vi.mocked(fetchVault).mockReset().mockResolvedValue([])
  })

  it('首页来源定位真实生日编辑器，保留明确分类和首页渠道', async () => {
    const session = useSessionStore(pinia)
    const me = { id: 'wg', name: '验收', initial: '验', color: '#4358D6', role: '成员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    const item: FamilyImportantDate = { id: 'birthday1', name: '生日测试', eventDate: '2026-10-06', note: null,
      reminderEnabled: false, reminderDaysBefore: 0, reminderTime: null, calendarType: 'SOLAR',
      lunarMonth: null, lunarDay: null, lunarLeapMonth: false, solarDateLabel: '2026-10-06',
      lunarDateLabel: '', notificationType: 'DIRECT', chatId: null, chatName: null,
      eventKind: 'BIRTHDAY', birthdaySubject: 'SELF', homeReminderEnabled: true }
    vi.mocked(fetchFamilyImportantDates).mockResolvedValue({ ...emptyImportantDates, items: [item] })
    await router.push('/calendar?date=2026-10-06&importantDateId=birthday1')
    await router.isReady()
    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    expect(wrapper.get('.important-date-modal').text()).toContain('编辑提醒事项')
    const selections = wrapper.findAll('.important-date-field select')
    expect(selections.map(select => (select.element as HTMLSelectElement).value)).toEqual(['BIRTHDAY', 'SELF'])
    expect(wrapper.get('input[aria-label="首页轻提醒"]').element).toHaveProperty('checked', true)
    expect(wrapper.get('.date-picker').element).toHaveProperty('value', '2026-10-06')
  })

  it('默认只看当前人，移除待安排清单并保留时间段创建', async () => {
    const data = useDataStore(pinia)
    const session = useSessionStore(pinia)
    const ui = useUiStore(pinia)
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

    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.find('.backlog').exists()).toBe(false)
    expect(wrapper.text()).toContain('我的已排期 P2')
    expect(wrapper.text()).not.toContain('他人的任务')
    expect(wrapper.find('.attendance-sidebar').isVisible()).toBe(false)
    expect(wrapper.find('.calendar-display-menu').exists()).toBe(false)
    expect(wrapper.findAll('.calendar-visibility [role="switch"]')).toHaveLength(4)
    const auxiliary = wrapper.get('.calendar-auxiliary')
    expect(auxiliary.attributes('open')).toBeUndefined()
    expect(wrapper.get('.completed-toggle').element.closest('details')).toBeNull()

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

  it('移动跨日执行安排保留截止，失败保留并重试，撤销不覆盖后续修改', async () => {
    const data = useDataStore(pinia), session = useSessionStore(pinia)
    const me = { id: 'wg', name: '验收', initial: '验', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' }); session.members = [me]
    data.tasks = [{ ...data.tasks[0]!, id: 'schedule', title: '周三执行周五截止', owner: me.id, status: '待办',
      start: '2026-10-07', due: '2026-10-09', startAt: '2026-10-07T23:00', endAt: '2026-10-08T02:00', editVersion: 'v0', subs: [], activity: [] }]
    let version = 0
    vi.mocked(apiPutTask).mockImplementation(async (_id, task) => ({ ...task, editVersion: `v${++version}` }))
    const w = mount(CalendarView, { global: { plugins: [pinia, router] } })
    await w.get('[aria-label="选择日历日期"]').setValue('2026-10-07')
    await flushPromises()
    async function drag(from: string, to: string) {
      await w.get(`[data-date="${from}"] .event`).trigger('dragstart')
      await w.get(`[data-date="${to}"]`).trigger('drop')
      await flushPromises()
    }
    await drag('2026-10-07', '2026-10-08')
    expect(data.tasks[0]).toMatchObject({ due: '2026-10-09', startAt: '2026-10-08T23:00', endAt: '2026-10-09T02:00:00' })
    expect(w.get('.schedule-feedback').text()).toContain('已保存')
    await w.findAll('.schedule-feedback button').find(b => b.text() === '撤销本次安排')!.trigger('click')
    await flushPromises()
    expect(data.tasks[0]!.startAt).toBe('2026-10-07T23:00')
    vi.mocked(apiPutTask).mockRejectedValueOnce(new Error('专用故障'))
    await drag('2026-10-07', '2026-10-08')
    expect(w.get('.schedule-feedback').text()).toContain('保存失败')
    expect(data.tasks[0]!.due).toBe('2026-10-09')
    await w.findAll('.schedule-feedback button').find(b => b.text() === '重试保存安排')!.trigger('click')
    await flushPromises()
    expect(w.get('.schedule-feedback').text()).toContain('撤销本次安排')
    data.tasks[0]!.editVersion = 'remote-newer'
    await flushPromises()
    expect(w.get('.schedule-feedback').text()).not.toContain('撤销本次安排')
    expect(apiPutTask).toHaveBeenCalledTimes(4)
  })

  it('合并日期选择后跨月自动收敛到有效日期，搜索可点击展开', async () => {
    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    await wrapper.get('[aria-label="选择日历日期"]').setValue('2026-01-31')
    await wrapper.get('[aria-label="下一时间段"]').trigger('click')
    expect((wrapper.get('[aria-label="选择日历日期"]').element as HTMLInputElement).value).toBe('2026-02-28')
    await wrapper.get('.search-toggle').trigger('click')
    expect(wrapper.get('.search-toggle').attributes('aria-expanded')).toBe('true')
  })

  it('关闭已完成后月、周、日仍保留未收尾子项，独立下级任务不级联改写父状态', async () => {
    const data = useDataStore(pinia), session = useSessionStore(pinia)
    const me = { id: 'wg', name: '验收', initial: '验', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' }); session.members = [me]
    const source = { ...data.tasks[0]!, owner: me.id, due: data.today, parts: [], steps: [], subs: [], files: [], docs: [], linkTasks: [], comments: [], custom: [], activity: [], images: [] }
    data.tasks = [
      { ...source, id: 'pending-sub', title: '父完成子待验', status: '已完成', startAt: `${data.today}T09:00`, endAt: `${data.today}T10:00`, subs: [{ text: '待验证', done: false }] },
      { ...source, id: 'closed', title: '全部收尾', status: '已完成' },
      { ...source, id: 'child', parentTaskId: 'closed', title: '独立下级待验证', status: '待验证' },
    ]
    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } }); await flushPromises()
    await wrapper.get('.completed-toggle').trigger('click')
    for (const index of [1, 2, 3]) {
      await wrapper.findAll('.view-switch button')[index]!.trigger('click')
      expect(wrapper.text()).toContain('父完成子待验 · 仍有 1 项子项未收尾')
      expect(wrapper.text()).toContain('独立下级待验证')
      expect(wrapper.text()).not.toContain('全部收尾')
    }
    expect(data.tasks[0]!.status).toBe('已完成')
    data.tasks[0]!.subs[0]!.done = true; await flushPromises()
    expect(wrapper.text()).not.toContain('父完成子待验')
    data.tasks[0]!.status = '待办'; await flushPromises()
    expect(wrapper.text()).toContain('父完成子待验')
  })

  it('已完成任务保留在原日期，月视图和时间轴均以删除线显示', async () => {
    const data = useDataStore(pinia)
    const session = useSessionStore(pinia)
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

    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } })
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
    expect(wrapper.find('.completed-toggle').attributes('aria-checked')).toBe('false')
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
    const data = useDataStore(pinia)
    const session = useSessionStore(pinia)
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    vi.mocked(fetchAlternateWorkWeek).mockResolvedValue({
      enabled: true,
      anchorWeek: data.today,
      anchorType: 'small',
    })

    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.find('.work-schedule').text()).toContain('本周 · 小周 · 双休')
    const marks = wrapper.findAll('.work-week-mark').map((mark) => mark.text())
    expect(marks).toContain('小周 · 双休')
    expect(marks).toContain('大周 · 周日上班')

    await wrapper.find('.work-week-visibility').trigger('click')
    expect(wrapper.findAll('.work-week-mark')).toHaveLength(0)
    expect(wrapper.find('.work-week-visibility').attributes('aria-checked')).toBe('false')
    expect(localStorage.getItem('wao-calendar-work-week-visible:wg')).toBe('false')

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
    const data = useDataStore(pinia)
    const session = useSessionStore(pinia)
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    vi.mocked(saveAlternateWorkWeek).mockRejectedValueOnce(new Error('网络暂不可用'))

    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } })
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
    const data = useDataStore(pinia)
    const session = useSessionStore(pinia)
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

    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.text()).toContain('中国节假日')
    expect(wrapper.text()).toContain('国际节日')
    expect(wrapper.text()).toContain('母亲节')
    expect(wrapper.text()).toContain('春节调休上班')
    expect(wrapper.find('.day-mark.work').text()).toBe('班')
    expect(wrapper.findAll('.work-week-mark').length).toBeGreaterThan(0)
  })

  it('节假日加载失败时给出可用的重试入口', async () => {
    const session = useSessionStore(pinia)
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    vi.mocked(fetchHolidayEvents)
      .mockRejectedValueOnce(new Error('网络失败'))
      .mockResolvedValueOnce(emptyHolidayCalendar)

    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    const retry = wrapper.find('button.holiday-status.error')
    expect(retry.text()).toContain('节假日加载失败')

    await retry.trigger('click')
    await flushPromises()
    expect(wrapper.find('button.holiday-status.error').exists()).toBe(false)
    expect(fetchHolidayEvents).toHaveBeenCalledTimes(2)
  })

  it('国际节日默认不添加，用户可主动订阅类别并从节日详情创建和关闭提醒', async () => {
    const data = useDataStore(pinia)
    const session = useSessionStore(pinia)
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

    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } })
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

  it('可维护每年显示的家庭重要日，并设置提前站内提醒', async () => {
    const data = useDataStore(pinia)
    const session = useSessionStore(pinia)
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

    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    await wrapper.find('.important-date-entry').trigger('click')
    expect(wrapper.find('.important-date-empty').text()).toContain('还没有提醒事项')

    await wrapper.find('.important-date-empty button').trigger('click')
    await flushPromises()
    await wrapper.find('.important-date-field input[type="text"]').setValue('妈妈生日')
    await wrapper.find('.important-date-field input[type="date"]').setValue(data.today)
    await wrapper.find('.important-date-field textarea').setValue('记得提前订花')
    await wrapper.find('input[aria-label="提醒通知"]').setValue(true)
    await wrapper.find('.important-date-field input[type="number"]').setValue(3)
    await wrapper.find('.important-date-field input[type="time"]').setValue('08:30')
    await wrapper.find('.important-date-form').trigger('submit')
    await flushPromises()

    expect(createFamilyImportantDate).toHaveBeenCalledWith({
      name: '妈妈生日', eventDate: data.today, note: '记得提前订花', reminderEnabled: true,
      reminderDaysBefore: 3, reminderTime: '08:30',
      calendarType: 'SOLAR', lunarMonth: 1, lunarDay: 1, lunarLeapMonth: false,
      notificationType: 'GROUP', chatId: null, chatName: null,
      eventKind: 'IMPORTANT', birthdaySubject: 'OTHER', homeReminderEnabled: true,
    })
    expect(wrapper.find('.important-date-item').text()).toContain('提前 3 天 08:30 WAO 通知中心提醒')
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

  it('从日历可分流创建农历站内提醒，并可搜索后跳到当日预览', async () => {
    const data = useDataStore(pinia)
    const session = useSessionStore(pinia)
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

    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } })
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
    expect(wrapper.text()).not.toContain('目标飞书群')
    expect(wrapper.text()).toContain('WAO 内部提醒')

    await wrapper.find('.cd-close').trigger('click')
    await wrapper.find('.calendar-search input').setValue('农历生日')
    expect(wrapper.find('.calendar-search-results').text()).toContain('农历生日')
    await wrapper.find('.calendar-search-results button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.important-date-modal').exists()).toBe(true)
    expect(wrapper.find('.important-date-form input[type="text"]').element).toHaveProperty('value', '农历生日')
  })

  it('考勤模式可独立开关，并在日历分开显示上下班与两位小数工时', async () => {
    const data = useDataStore(pinia)
    const session = useSessionStore(pinia)
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    const previousDate = new Date(`${data.today}T12:00:00Z`)
    previousDate.setUTCDate(previousDate.getUTCDate() - 1)
    const previous = previousDate.toISOString().slice(0, 10)
    const attendance = {
      from: `${data.today.slice(0, 7)}-01`, to: data.today,
      config: { enabled: true, username: 's008500@nl-ds.cn', vaultItemId: 'vi1', status: 'OK', lastSyncedAt: `${data.today}T09:05:00`, lastError: null, historySyncedFrom: '2026-08-01', historyTargetFrom: '2025-10-01', historyComplete: false },
      days: [
        ...Array.from({ length: 28 }, (_, index) => `${data.today.slice(0, 7)}-${String(index + 1).padStart(2, '0')}`)
          .filter(date => date !== previous && date !== data.today).slice(0, 17)
          .map(date => ({ date, clockInAt: null, clockOutAt: null, dayType: 'WORKDAY', anomaly: null, manualOverride: false, workHours: null, expectedWorkday: true, workWeekLabel: '工作日' })),
        { date: previous, clockInAt: `${previous}T09:00:00`, clockOutAt: `${previous}T18:00:00`, dayType: 'WORKDAY', anomaly: null, manualOverride: false, workHours: 8, expectedWorkday: true, workWeekLabel: '大周 · 周日上班' },
        { date: data.today, clockInAt: `${data.today}T09:00:00`, clockOutAt: `${data.today}T21:00:00`, dayType: 'WORKDAY', anomaly: null, manualOverride: false, workHours: 11, expectedWorkday: true, workWeekLabel: '大周 · 周日上班' },
      ],
      totalHours: 19, monthDailyAverageHours: 9.5, validDays: 2,
      weeks: [{ from: previous, to: data.today, label: '大周 · 周日上班', averageHours: 9.5, validDays: 2 }],
    } as const
    vi.mocked(fetchAttendance).mockResolvedValue(attendance as never)

    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    expect(wrapper.text()).not.toContain('11.00 h')
    await wrapper.find('.attendance-toggle').trigger('click')
    await flushPromises()
    expect(fetchAttendance).toHaveBeenCalled()
    expect(wrapper.get('.attendance-sidebar').classes()).toContain('folded')
    expect(wrapper.get('.attendance-sidebar-content').attributes('style')).toContain('display: none')
    await wrapper.get('.attendance-sidebar-toggle').trigger('click')
    expect(wrapper.get('.attendance-sidebar-toggle').attributes('aria-expanded')).toBe('true')
    expect(wrapper.text()).toContain('11.00 h')
    const attendanceEvents = wrapper.findAll('.event').map((event) => event.text())
    expect(attendanceEvents).toContain('09:00上班打卡')
    expect(attendanceEvents).toContain('21:00下班打卡')
    expect(attendanceEvents).not.toContain('当日工时 · 11.00 h')
    expect(wrapper.get(`[data-date="${data.today}"] .day-heading`).text()).toContain('11.00 h')
    await wrapper.get(`[data-date="${data.today}"] .day-attendance`).trigger('click')
    expect(wrapper.find('.day-modal').text()).toContain('有效工时')
    expect(wrapper.find('.attendance-goals').text()).toContain('9.50 / 10.00 h')
    expect(wrapper.find('.attendance-goals').text()).toContain('1 / 3 天')
    expect(wrapper.find('.attendance-goals').text()).toContain('本月还可使用 2 天')
    expect(wrapper.find('.attendance-goals').text()).toContain('已满足 19:30 最低下班要求')
    expect(wrapper.find('.attendance-history').text()).toContain('已回溯至 2026-08-01')
    expect(wrapper.find('.attendance-history').text()).toContain('失败后从原进度续跑')
    await wrapper.find('.attendance-toggle').trigger('click')
    expect(wrapper.find('.attendance-panel').exists()).toBe(false)
  })

  it('首次配置考勤时自动启用表单并忽略旧的禁用草稿', async () => {
    const data = useDataStore(pinia)
    const session = useSessionStore(pinia)
    const me = { id: 'wg', name: '娃哥', initial: '哥', color: '#4358D6', role: '管理员' }
    session.accept({ accountId: 'a1', member: me, identities: [], createdAt: '2026-01-01T00:00:00' })
    session.members = [me]
    localStorage.setItem('wao-attendance-config-draft:wg', JSON.stringify({
      enabled: false, username: '', vaultItemId: '',
    }))
    vi.mocked(fetchAttendance).mockResolvedValue({
      from: data.today, to: data.today,
      config: { enabled: false, username: null, vaultItemId: null, status: 'UNCONFIGURED', lastSyncedAt: null, lastError: null, historySyncedFrom: null, historyTargetFrom: '2025-10-01', historyComplete: false },
      days: [], totalHours: 0, monthDailyAverageHours: 0, validDays: 0, weeks: [],
    })

    vi.mocked(fetchVault).mockResolvedValue([{
      id: 'vg1', group: '工作', items: [{
        id: 'vi1', name: '摩卡-工时登录系统', icon: 'ri-key-line', user: 's008500@nl-ds.cn',
        site: 'https://core.mokahr.com', note: '', updated: '刚刚', hasSecret: true,
      }],
    }])
    const wrapper = mount(CalendarView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    await wrapper.find('.attendance-toggle').trigger('click')
    await flushPromises()
    await wrapper.findAll('.attendance-actions button').find((button) => button.text().includes('设置'))!.trigger('click')

    expect((wrapper.find('.attendance-modal .toggle input').element as HTMLInputElement).checked).toBe(true)
    expect(wrapper.find('.attendance-modal fieldset').attributes('disabled')).toBeUndefined()
    expect((wrapper.find('.attendance-modal input[type="email"]').element as HTMLInputElement).disabled).toBe(false)
    expect((wrapper.find('.attendance-modal select').element as HTMLSelectElement).disabled).toBe(false)
    await flushPromises()
    await wrapper.find('.attendance-modal select').setValue('vi1')
    await flushPromises()

    const email = wrapper.find('.attendance-modal input[type="email"]')
    expect(email.attributes('readonly')).toBeDefined()
    expect((email.element as HTMLInputElement).value).toBe('s008500@nl-ds.cn')
  })
})
