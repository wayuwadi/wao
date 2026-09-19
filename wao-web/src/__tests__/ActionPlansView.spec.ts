import { flushPromises, mount, enableAutoUnmount } from '@vue/test-utils'
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

vi.mock('@/stores/session', async () => {
  const { reactive } = await import('vue')
  return { useSessionStore: () => reactive({ userId: 'member-a' }) }
})
enableAutoUnmount(afterEach)

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
    vi.useFakeTimers()
    localStorage.clear()
    vi.mocked(fetchActionPlans).mockResolvedValue([plan])
    vi.mocked(toggleActionCheckin).mockResolvedValue({ ...plan, completedCount: 1, currentStreak: 1,
      totalCompletedDays: 1, items: [{ ...plan.items[0]!, completed: true }] })
    vi.mocked(saveActionNote).mockResolvedValue(plan)
  })
  afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.clearAllMocks() })

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
  it('失败后重试重新请求并保留输入', async () => {
    vi.mocked(saveActionNote).mockRejectedValueOnce(new Error('断网')).mockResolvedValueOnce({ ...plan, note: '保留复盘' })
    const wrapper = mount(ActionPlansView)
    await flushPromises()
    await wrapper.get('textarea').setValue('保留复盘')
    await vi.advanceTimersByTimeAsync(650)
    expect(wrapper.text()).toContain('保存失败 · 重试')
    expect(localStorage.getItem(`wao-action-note-v1:member-a:ap1:${today}`)).toContain('保留复盘')
    await wrapper.get('.note-title button').trigger('click')
    await flushPromises()
    expect(saveActionNote).toHaveBeenCalledTimes(2)
    expect(saveActionNote).toHaveBeenLastCalledWith('ap1', today, '保留复盘')
    expect(wrapper.text()).toContain('已保存')
    expect(localStorage.getItem(`wao-action-note-v1:member-a:ap1:${today}`)).toBeNull()
  })
  it('慢回包不覆盖新输入，串行发送最新内容', async () => {
    let finish!: (value: ActionPlan) => void
    vi.mocked(saveActionNote).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const wrapper = mount(ActionPlansView)
    await flushPromises()
    await wrapper.get('textarea').setValue('第一版')
    await vi.advanceTimersByTimeAsync(650)
    await wrapper.get('textarea').setValue('最新版本')
    await vi.advanceTimersByTimeAsync(650)
    expect(saveActionNote).toHaveBeenCalledTimes(1)
    finish({ ...plan, note: '第一版' })
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('最新版本')
    expect(saveActionNote).toHaveBeenLastCalledWith('ap1', today, '最新版本')
    expect(saveActionNote).toHaveBeenCalledTimes(2)
  })
  it('打卡回包不覆盖正在编辑的复盘', async () => {
    const wrapper = mount(ActionPlansView)
    await flushPromises()
    await wrapper.get('textarea').setValue('正在写的复盘')
    await wrapper.get('.action-row').trigger('click')
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('正在写的复盘')
    await vi.advanceTimersByTimeAsync(650)
    expect(saveActionNote).toHaveBeenLastCalledWith('ap1', today, '正在写的复盘')
  })
  it('日期切换后旧保存仍归原日期，回包不污染当前日期', async () => {
    let finish!: (value: ActionPlan) => void
    vi.mocked(saveActionNote).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    vi.mocked(fetchActionPlans).mockImplementation(async date => [{ ...plan, selectedDate: date, note: date === today ? '' : '昨天的内容' }])
    const wrapper = mount(ActionPlansView)
    await flushPromises()
    await wrapper.get('textarea').setValue('今天的内容')
    await vi.advanceTimersByTimeAsync(650)
    await wrapper.get('[aria-label="前一天"]').trigger('click')
    await flushPromises()
    finish({ ...plan, note: '今天的内容' })
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('昨天的内容')
    expect(saveActionNote).toHaveBeenLastCalledWith('ap1', today, '今天的内容')
  })
  it('重新进入恢复本人成员草稿，确认后保存', async () => {
    localStorage.setItem(`wao-action-note-v1:member-b:ap1:${today}`, JSON.stringify({ content: '其他成员内容' }))
    localStorage.setItem(`wao-action-note-v1:member-a:ap1:${today}`, JSON.stringify({ content: '恢复草稿' }))
    const wrapper = mount(ActionPlansView)
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('恢复草稿')
    expect(wrapper.text()).toContain('草稿待保存')
    expect(saveActionNote).not.toHaveBeenCalled()
    await wrapper.get('.note-title button').trigger('click')
    await flushPromises()
    expect(saveActionNote).toHaveBeenLastCalledWith('ap1', today, '恢复草稿')
  })
  it('本机存储失败时提示备份，仍可保存到服务端', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('存储已满') })
    const wrapper = mount(ActionPlansView)
    await flushPromises()
    await wrapper.get('textarea').setValue('需要保留')
    expect(wrapper.text()).toContain('本机草稿未能保存')
    await vi.advanceTimersByTimeAsync(650)
    expect(wrapper.text()).toContain('已保存')
    storage.mockRestore()
  })

  it('不同计划的防抖保存互不覆盖', async () => {
    vi.mocked(fetchActionPlans).mockResolvedValue([plan, { ...plan, id: 'ap2', title: '第二个计划', note: '原有内容' }])
    const wrapper = mount(ActionPlansView)
    await flushPromises()
    await wrapper.get('textarea').setValue('计划一复盘')
    const buttons = wrapper.findAll('button')
    await buttons.find(button => button.text().includes('第二个计划'))!.trigger('click')
    await wrapper.get('textarea').setValue('计划二复盘')
    await vi.advanceTimersByTimeAsync(650)
    expect(saveActionNote).toHaveBeenCalledWith('ap1', today, '计划一复盘')
    expect(saveActionNote).toHaveBeenCalledWith('ap2', today, '计划二复盘')
    expect(wrapper.get('textarea').element.value).toBe('计划二复盘')
  })
  it('没有本人草稿时不读取其他成员的内容', async () => {
    localStorage.setItem(`wao-action-note-v1:member-b:ap1:${today}`, JSON.stringify({ content: '其他成员内容' }))
    const wrapper = mount(ActionPlansView)
    await flushPromises()
    expect(wrapper.get('textarea').element.value).toBe('')
  })

  it('仅恢复草稿但未确认时，离开页面不会自动覆盖云端', async () => {
    localStorage.setItem(`wao-action-note-v1:member-a:ap1:${today}`, JSON.stringify({ content: '未确认旧稿' }))
    const wrapper = mount(ActionPlansView)
    await flushPromises()
    wrapper.unmount()
    await flushPromises()
    expect(saveActionNote).not.toHaveBeenCalled()
    expect(localStorage.getItem(`wao-action-note-v1:member-a:ap1:${today}`)).toContain('未确认旧稿')
  })

})
