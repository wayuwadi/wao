import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WorkdayRhythmView from '@/views/WorkdayRhythmView.vue'
import {
  fetchWorkdayRhythm,
  fetchWorkdayRhythmDay,
  saveWorkdayRhythm,
  saveWorkdayRhythmDay,
  sendEnergyNotification,
} from '@/api/workdayRhythm'
import { fetchFeishuChats } from '@/api/system'
import {
  DEFAULT_WORKDAY_RHYTHM,
  cloneRhythmConfig,
  defaultRhythmDay,
  shanghaiClock,
} from '@/utils/workdayRhythm'

vi.mock('@/api/workdayRhythm', () => ({
  fetchWorkdayRhythm: vi.fn<typeof fetchWorkdayRhythm>(),
  fetchWorkdayRhythmDay: vi.fn<typeof fetchWorkdayRhythmDay>(),
  saveWorkdayRhythm: vi.fn<typeof saveWorkdayRhythm>(),
  saveWorkdayRhythmDay: vi.fn<typeof saveWorkdayRhythmDay>(),
  sendEnergyNotification: vi.fn<typeof sendEnergyNotification>(),
}))

vi.mock('@/api/system', () => ({ fetchFeishuChats: vi.fn<typeof fetchFeishuChats>() }))

const clock = shanghaiClock()

function mountView() {
  return mount(WorkdayRhythmView, {
    global: { stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

describe('WorkdayRhythmView', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchWorkdayRhythm).mockResolvedValue(cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM))
    vi.mocked(fetchWorkdayRhythmDay).mockResolvedValue(defaultRhythmDay(clock.date, clock.weekday))
    vi.mocked(saveWorkdayRhythmDay).mockResolvedValue()
    vi.mocked(saveWorkdayRhythm).mockResolvedValue(cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM))
    vi.mocked(sendEnergyNotification).mockResolvedValue({ sent: true, chatName: '娃屋日常' })
    vi.mocked(fetchFeishuChats).mockResolvedValue([{ chatId: 'oc_daily', name: '娃屋日常' }])
  })
  afterEach(() => vi.clearAllMocks())

  it('展示完整真实模板并保存时间块打卡', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.findAll('.time-block')).toHaveLength(7)
    expect(wrapper.text()).toContain('重点攻坚')
    await wrapper.findAll('.time-block')[0]!.trigger('click')
    await flushPromises()

    expect(saveWorkdayRhythmDay).toHaveBeenCalledWith(
      expect.objectContaining({ completed: ['morning'] }),
    )
    expect(wrapper.find('.timeline-card').text()).toContain('1 / 7 已完成')
    wrapper.unmount()
  })

  it('把本人填写的今日重点持久化而不生成任务数据', async () => {
    const wrapper = mountView()
    await flushPromises()
    const inputs = wrapper.findAll('.focus-card input')
    await inputs[0]!.setValue('完成上线链路验收')
    await wrapper.get('.focus-card footer button').trigger('click')
    await flushPromises()

    expect(saveWorkdayRhythmDay).toHaveBeenCalledWith(
      expect.objectContaining({
        focus: ['完成上线链路验收', '', ''],
      }),
    )
    expect(wrapper.text()).toContain('已保存到你的 WAO 账号')
    wrapper.unmount()
  })

  it('保存失败时保留本机草稿并提供原位重试', async () => {
    vi.mocked(saveWorkdayRhythmDay).mockRejectedValueOnce(new Error('网络暂不可用'))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('.energy-action.water').trigger('click')
    await flushPromises()

    expect(wrapper.get('.save-banner.error').text()).toContain('未提交内容仍在本机')
    expect(localStorage.getItem(`wao-workday-rhythm-day-draft-${clock.date}`)).toContain(
      '"waterCount":1',
    )
    await wrapper.get('.save-banner.error button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.save-banner.saved').text()).toContain('已保存')
    wrapper.unmount()
  })

  it('调整模板时拒绝时间空洞并保存首尾相接的修改', async () => {
    const wrapper = mountView()
    await flushPromises()
    await wrapper.get('.settings-button').trigger('click')
    const timeInputs = wrapper.findAll('.block-editor input[type="time"]')
    await timeInputs[1]!.setValue('08:30')
    await wrapper.get('.editor .primary').trigger('click')
    expect(wrapper.get('.editor-error').text()).toContain('首尾相接')
    expect(saveWorkdayRhythm).not.toHaveBeenCalled()

    await timeInputs[2]!.setValue('08:30')
    await wrapper.get('.editor .primary').trigger('click')
    await flushPromises()
    expect(saveWorkdayRhythm).toHaveBeenCalledWith(
      expect.objectContaining({
        blocks: expect.arrayContaining([
          expect.objectContaining({ id: 'morning', end: '08:30' }),
          expect.objectContaining({ id: 'deep-work', start: '08:30' }),
        ]),
      }),
    )
    expect(wrapper.find('.editor').exists()).toBe(false)
    wrapper.unmount()
  })

  it('从精力卡设置本人通知群并随节奏配置跨设备保存', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.energy-card header button').trigger('click')
    await flushPromises()
    await wrapper.get('.toggle-field input').setValue(true)
    await flushPromises()
    await wrapper.get('.chat-field select').setValue('oc_daily')
    await wrapper.get('.editor .primary').trigger('click')
    await flushPromises()

    expect(saveWorkdayRhythm).toHaveBeenCalledWith(
      expect.objectContaining({
        energyNotification: {
          enabled: true,
          chatId: 'oc_daily',
          chatName: '娃屋日常',
          notifyWater: true,
          notifyMeditation: true,
        },
      }),
    )
    wrapper.unmount()
  })

  it('喝水记录落库后通知本人配置的群，失败时可原位重试', async () => {
    const configured = cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM)
    configured.energyNotification = {
      enabled: true,
      chatId: 'oc_daily',
      chatName: '娃屋日常',
      notifyWater: true,
      notifyMeditation: true,
    }
    vi.mocked(fetchWorkdayRhythm).mockResolvedValue(configured)
    vi.mocked(sendEnergyNotification)
      .mockRejectedValueOnce(new Error('飞书暂时不可用'))
      .mockResolvedValueOnce({ sent: true, chatName: '娃屋日常' })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.energy-action.water').trigger('click')
    await flushPromises()
    expect(saveWorkdayRhythmDay).toHaveBeenCalledWith(expect.objectContaining({ waterCount: 1 }))
    expect(sendEnergyNotification).toHaveBeenCalledWith('WATER', clock.date)
    expect(wrapper.get('.energy-notice.error').text()).toContain('群通知未发出')

    await wrapper.get('.energy-notice.error button').trigger('click')
    await flushPromises()
    expect(sendEnergyNotification).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.energy-notice.sent').text()).toContain('群通知已发出')
    wrapper.unmount()
  })
})
