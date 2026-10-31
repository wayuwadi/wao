import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import AttendancePanel from '@/components/calendar/AttendancePanel.vue'
import { fetchAttendance, syncAttendance, type AttendanceView } from '@/api/attendance'
import { useDataStore } from '@/stores/data'
import type { HolidayEvent } from '@/api/holiday'

vi.mock('@/api/attendance', () => ({ fetchAttendance: vi.fn(), syncAttendance: vi.fn(), saveAttendanceConfig: vi.fn(), saveAttendanceDay: vi.fn(), clearAttendanceDay: vi.fn() }))
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))
enableAutoUnmount(afterEach)
afterEach(() => vi.restoreAllMocks())

const view = (status: AttendanceView['config']['status'], lastError: string | null = null): AttendanceView => ({
  from: '2026-10-01', to: '2026-10-29',
  config: { enabled: true, username: 'test@example.com', vaultItemId: 'v1', status, lastError,
    lastSyncedAt: null, historySyncedFrom: null, historyTargetFrom: '2025-11-01', historyComplete: false },
  days: [], totalHours: 0, monthDailyAverageHours: 0, validDays: 0, weeks: [],
})

async function panel() {
  const pinia = createPinia()
  setActivePinia(pinia)
  vi.spyOn(useDataStore(pinia), 'reloadVault').mockResolvedValue(true)
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  await router.push('/')
  const wrapper = mount(AttendancePanel, { props: { active: true, from: '2026-10-01', to: '2026-10-29', selectedDate: null }, global: { plugins: [pinia, router] } })
  await flushPromises()
  return wrapper
}

describe('Moka 会话确认恢复', () => {
  beforeEach(() => {
    vi.mocked(fetchAttendance).mockReset()
    vi.mocked(syncAttendance).mockReset().mockResolvedValue(view('OK'))
  })

  it.each(['NEEDS_CONFIRMATION', 'NEEDS_LOGIN'] as const)('取消 %s 的接管确认不会发送同步请求', async status => {
    vi.mocked(fetchAttendance).mockResolvedValue(view(status, 'Moka 检测到其他会话'))
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = await panel()
    await wrapper.vm.sync()
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('其他设备上的 Moka 登录失效'))
    expect(syncAttendance).not.toHaveBeenCalled()
  })

  it('单次确认恢复后普通同步不携带接管授权', async () => {
    vi.mocked(fetchAttendance).mockResolvedValueOnce(view('NEEDS_CONFIRMATION')).mockResolvedValue(view('OK'))
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const wrapper = await panel()
    await wrapper.vm.sync()
    expect(syncAttendance).toHaveBeenNthCalledWith(1, '2026-10-01', '2026-10-29', true)
    await wrapper.vm.sync()
    expect(syncAttendance).toHaveBeenNthCalledWith(2, '2026-10-01', '2026-10-29', false)
    expect(confirm).toHaveBeenCalledTimes(1)
  })

  it('普通失败刷新确认状态，下次重试才允许接管', async () => {
    vi.mocked(fetchAttendance).mockResolvedValueOnce(view('READY')).mockResolvedValue(view('NEEDS_CONFIRMATION'))
    vi.mocked(syncAttendance).mockRejectedValueOnce(new Error('Moka 检测到其他会话'))
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const wrapper = await panel()
    await wrapper.vm.sync()
    expect(wrapper.text()).toContain('其他会话')
    expect(confirm).not.toHaveBeenCalled()
    await wrapper.vm.sync()
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(syncAttendance).toHaveBeenCalledTimes(1)
  })
})

describe('19:30 前下班的月度额度', () => {
  it('法定放假不占工作日，调休上班计入，节假日失败时不显示错误额度', async () => {
    const current = view('OK')
    current.days = Array.from({ length: 22 }, (_, index) => {
      const date = `2026-10-${String(index + 1).padStart(2, '0')}`
      return { date, expectedWorkday: index < 20, dayType: 'WORKDAY' as const,
        clockInAt: `${date}T08:30:00`, clockOutAt: `${date}T18:30:00`,
        workHours: 9, anomaly: null, manualOverride: false, workWeekLabel: '工作日' }
    })
    const holiday = (date: string, dayType: HolidayEvent['dayType']): HolidayEvent => ({
      date, dayType, category: 'CHINA', id: date, code: date, year: 2026, name: '测试假期',
      internationalCategory: null, sourceName: '测试', sourceUrl: null,
      remindable: false, reminderEnabled: false, reminderTime: null,
    })
    vi.mocked(fetchAttendance).mockResolvedValue(current)
    const wrapper = await panel()
    await wrapper.setProps({ holidayState: 'ready', holidays: [
      holiday('2026-10-01', 'DAY_OFF'), holiday('2026-10-02', 'DAY_OFF'),
      holiday('2026-10-21', 'ADJUSTED_WORKDAY'), holiday('2026-10-03', 'FESTIVAL'),
    ] })
    expect(wrapper.findAll('.attendance-goal')[2]!.text()).toContain('19 / 3 天')
    await wrapper.setProps({ holidayState: 'error' })
    expect(wrapper.text()).toContain('节假日读取失败，暂无法计算额度')
    expect(wrapper.text()).not.toContain('本月可用')
  })
  it.each([[19, 3], [20, 4], [22, 4], [24, 4], [25, 5], [0, 0]])(
    '%i 个应工作日允许 %i 天，包含尚未出勤、请假和调休工作日', async (workdays, allowance) => {
      const current = view('OK')
      current.days = Array.from({ length: 31 }, (_, index) => ({
        date: `2026-10-${String(index + 1).padStart(2, '0')}`,
        expectedWorkday: index < workdays, dayType: index === 0 ? 'LEAVE' : 'WORKDAY',
        clockInAt: null, clockOutAt: null, workHours: null, anomaly: null,
        manualOverride: false, workWeekLabel: '工作日',
      }))
      vi.mocked(fetchAttendance).mockResolvedValue(current)
      const wrapper = await panel()
      const goal = wrapper.findAll('.attendance-goal')[2]!
      expect(goal.text()).toContain(`本月可用 ${allowance} 天`)
      expect(goal.text()).toContain(`0 / ${allowance} 天`)
      expect(goal.text()).toContain(`本月还可使用 ${allowance} 天`)
    },
  )

  it('切换月份重算额度与超额，19:30 整和跨天下班不占用例外', async () => {
    const current = view('OK')
    current.days = Array.from({ length: 19 }, (_, index) => {
      const date = `2026-10-${String(index + 1).padStart(2, '0')}`
      return { date, expectedWorkday: true, dayType: 'WORKDAY' as const,
        clockInAt: `${date}T08:30:00`,
        clockOutAt: index < 4 ? `${date}T19:29:00` : index === 4 ? '2026-10-06T01:00:00' : `${date}T19:30:00`,
        workHours: 10, anomaly: null, manualOverride: false, workWeekLabel: '工作日' }
    })
    vi.mocked(fetchAttendance).mockResolvedValue(current)
    const wrapper = await panel()
    expect(wrapper.findAll('.attendance-goal')[2]!.text()).toContain('4 / 3 天已超出 1 天')
    expect(wrapper.findAll('.attendance-goal')[2]!.classes()).toContain('danger')
    vi.mocked(fetchAttendance).mockResolvedValue({ ...current, days: [...current.days, { ...current.days[0]!, date: '2026-10-20', clockOutAt: null, workHours: null }] })
    await wrapper.setProps({ from: '2026-11-01', to: '2026-11-30' })
    await flushPromises()
    expect(wrapper.findAll('.attendance-goal')[2]!.text()).toContain('4 / 4 天本月还可使用 0 天')
    expect(wrapper.findAll('.attendance-goal')[2]!.classes()).not.toContain('danger')
  })
})
