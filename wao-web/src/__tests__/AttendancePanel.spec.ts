import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import AttendancePanel from '@/components/calendar/AttendancePanel.vue'
import { fetchAttendance, syncAttendance, type AttendanceView } from '@/api/attendance'
import { useDataStore } from '@/stores/data'

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
