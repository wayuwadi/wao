import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ActionPlansView from '@/views/ActionPlansView.vue'
import {
  createActionPlan,
  fetchActionPlans,
  saveActionNote,
  setActionPlanCompleted,
  toggleActionCheckin,
  updateActionPlan,
  type ActionPlan,
} from '@/api/actionPlans'

vi.mock('@/api/actionPlans', () => ({
  createActionPlan: vi.fn(), fetchActionPlans: vi.fn(), saveActionNote: vi.fn(),
  setActionPlanCompleted: vi.fn(), toggleActionCheckin: vi.fn(), updateActionPlan: vi.fn(),
}))

const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
const plan: ActionPlan = {
  id: 'ap1', title: '100 天健康重启', vision: '把长期愿景变成今天的动作',
  startDate: today, targetDate: '2026-12-31', reminderTime: '20:00:00', reminderEnabled: true,
  status: 'ACTIVE', selectedDate: today,
  items: [{ id: 'ai1', title: '完成今天确认好的动作', weekdays: [1, 2, 3, 4, 5, 6, 7], active: true, scheduled: true, completed: false }],
  note: '', scheduledCount: 1, completedCount: 0, currentStreak: 0, totalCompletedDays: 0,
  reminderState: 'WAITING',
}

describe('ActionPlansView', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchActionPlans).mockResolvedValue([plan])
    vi.mocked(toggleActionCheckin).mockResolvedValue({ ...plan, completedCount: 1, currentStreak: 1,
      totalCompletedDays: 1, items: [{ ...plan.items[0]!, completed: true }] })
    vi.mocked(saveActionNote).mockResolvedValue(plan)
  })
  afterEach(() => vi.clearAllMocks())

  it('展示服务端真实计划并在点击后保存当日打卡', async () => {
    const wrapper = mount(ActionPlansView)
    await flushPromises()

    expect(wrapper.text()).toContain('100 天健康重启')
    expect(wrapper.text()).toContain('完成今天确认好的动作')
    await wrapper.get('.action-row').trigger('click')
    await flushPromises()

    expect(toggleActionCheckin).toHaveBeenCalledWith('ap1', 'ai1', today)
    expect(wrapper.text()).toContain('今日已完成')
  })

  it('真实空状态明确说明系统不会凭照片编训练处方', async () => {
    vi.mocked(fetchActionPlans).mockResolvedValue([])
    const wrapper = mount(ActionPlansView)
    await flushPromises()

    expect(wrapper.text()).toContain('先定方向，再安排第一周')
    expect(wrapper.text()).toContain('不会凭一张照片替你编训练处方')
  })
})
