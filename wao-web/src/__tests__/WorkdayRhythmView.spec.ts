import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WorkdayRhythmView from '@/views/WorkdayRhythmView.vue'
import {
  fetchWorkdayRhythmHistory,
  fetchWorkdayRhythm,
  fetchWorkdayRhythmDay,
  saveWorkdayRhythm,
  saveWorkdayRhythmDay,
  sendEnergyNotification,
  saveRhythmFocus,
  setRhythmWorkdayStatus,
} from '@/api/workdayRhythm'
import { fetchFeishuChats, fetchWebhookEndpoints } from '@/api/system'
import {
  DEFAULT_WORKDAY_RHYTHM,
  cloneRhythmConfig,
  defaultRhythmDay,
  shanghaiClock,
} from '@/utils/workdayRhythm'

vi.mock('@/api/workdayRhythm', () => ({
  fetchWorkdayRhythmHistory: vi.fn<typeof fetchWorkdayRhythmHistory>(),
  fetchWorkdayRhythm: vi.fn<typeof fetchWorkdayRhythm>(),
  fetchWorkdayRhythmDay: vi.fn<typeof fetchWorkdayRhythmDay>(),
  saveWorkdayRhythm: vi.fn<typeof saveWorkdayRhythm>(),
  saveWorkdayRhythmDay: vi.fn<typeof saveWorkdayRhythmDay>(),
  sendEnergyNotification: vi.fn<typeof sendEnergyNotification>(),
  saveRhythmFocus: vi.fn<typeof saveRhythmFocus>(),
  setRhythmWorkdayStatus: vi.fn<typeof setRhythmWorkdayStatus>(),
}))

vi.mock('@/api/system', () => ({
  fetchFeishuChats: vi.fn<typeof fetchFeishuChats>(),
  fetchWebhookEndpoints: vi.fn<typeof fetchWebhookEndpoints>(),
}))

const clock = shanghaiClock()

function mountView() {
  return mount(WorkdayRhythmView, {
    global: { stubs: {
      RouterLink: { template: '<a><slot /></a>' },
      WorkdayRhythmDailyPanel: true,
      WorkdayRhythmDailySettings: true,
    } },
  })
}

describe('WorkdayRhythmView', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchWorkdayRhythm).mockResolvedValue(cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM))
    vi.mocked(fetchWorkdayRhythmHistory).mockResolvedValue({
      from: `${clock.date.slice(0, 7)}-01`,
      to: `${clock.date.slice(0, 7)}-31`,
      skippedCount: 0,
      items: [
        {
          date: clock.date,
          workdayActive: true,
          focus: ['完成上线链路验收', '整理今日复盘'],
          energyItems: [
            { id: 'WATER', title: '喝水', mode: 'COUNT', count: 3 },
            { id: 'MEDITATION', title: '至少冥想一次', mode: 'DAILY_ONCE', count: 1 },
          ],
          checklist: [],
          documentId: null,
          documentKbId: null,
          documentTitle: null,
        },
      ],
    })
    vi.mocked(fetchWorkdayRhythmDay).mockResolvedValue(defaultRhythmDay(clock.date, clock.weekday))
    vi.mocked(saveWorkdayRhythmDay).mockResolvedValue()
    vi.mocked(saveWorkdayRhythm).mockResolvedValue(cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM))
    vi.mocked(sendEnergyNotification).mockResolvedValue({ sent: true, chatName: '娃屋日常' })
    vi.mocked(saveRhythmFocus).mockResolvedValue({} as never)
    vi.mocked(fetchFeishuChats).mockResolvedValue([{ chatId: 'oc_daily', name: '娃屋日常' }])
    vi.mocked(fetchWebhookEndpoints).mockResolvedValue([
      {
        id: 'fw_daily',
        name: '娃屋日常机器人',
        description: '精力补给',
        urlMask: 'https://open.feishu.cn/open-apis/bot/v2/hook/••••1234',
        urlSet: true,
        enabled: true,
        systemDefault: false,
        createdAt: '2026-08-20T10:00:00',
        updatedAt: '2026-08-20T10:00:00',
      },
    ])
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

  it('从调整节奏旁按月回顾真实每日目标和项目执行次数', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.history-button').trigger('click')
    await flushPromises()

    expect(fetchWorkdayRhythmHistory).toHaveBeenCalledWith(
      `${clock.date.slice(0, 7)}-01`,
      expect.stringMatching(new RegExp(`^${clock.date.slice(0, 7)}-\\d{2}$`)),
    )
    expect(wrapper.get('.history-dialog').text()).toContain('完成上线链路验收')
    expect(wrapper.get('.history-dialog').text()).toContain('喝水3 次')
    expect(wrapper.get('.history-dialog').text()).toContain('至少冥想一次已完成')
    wrapper.unmount()
  })

  it('整月存档都损坏时如实提示而不宣称没有保存记录', async () => {
    vi.mocked(fetchWorkdayRhythmHistory).mockResolvedValueOnce({
      from: `${clock.date.slice(0, 7)}-01`,
      to: `${clock.date.slice(0, 7)}-31`,
      skippedCount: 2,
      items: [],
    })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.history-button').trigger('click')
    await flushPromises()

    expect(wrapper.get('.history-dialog').text()).toContain('2 条已保存记录暂时无法读取')
    expect(wrapper.get('.history-dialog').text()).toContain('没有被伪装成空白日期')
    expect(wrapper.get('.history-dialog').text()).not.toContain('这个月还没有已保存记录')
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
    await wrapper.get('.channel-field select').setValue('CHAT')
    await flushPromises()
    await wrapper.get('.chat-field select').setValue('oc_daily')
    await wrapper.get('.editor .primary').trigger('click')
    await flushPromises()

    expect(saveWorkdayRhythm).toHaveBeenCalledWith(
      expect.objectContaining({
        energyNotification: expect.objectContaining({
          enabled: true,
          chatId: 'oc_daily',
          chatName: '娃屋日常',
        }),
      }),
    )
    wrapper.unmount()
  })

  it('喝水记录落库后通知本人配置的群，失败时可原位重试', async () => {
    const configured = cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM)
    configured.energyNotification = {
      enabled: true,
      channel: 'CHAT',
      webhookId: '',
      webhookName: '',
      chatId: 'oc_daily',
      chatName: '娃屋日常',
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
    expect(wrapper.get('.energy-notice.error').text()).toContain('飞书通知未发出')

    await wrapper.get('.energy-notice.error button').trigger('click')
    await flushPromises()
    expect(sendEnergyNotification).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.energy-notice.sent').text()).toContain('飞书通知已发出')
    wrapper.unmount()
  })

  it('可直接选择系统设置中的 Webhook 并按稳定配置 id 保存', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.energy-card header button').trigger('click')
    await wrapper.get('.toggle-field input').setValue(true)
    await flushPromises()
    await wrapper.get('.chat-field select').setValue('fw_daily')
    await wrapper.get('.editor .primary').trigger('click')
    await flushPromises()

    expect(fetchWebhookEndpoints).toHaveBeenCalled()
    expect(saveWorkdayRhythm).toHaveBeenCalledWith(
      expect.objectContaining({
        energyNotification: expect.objectContaining({
          enabled: true,
          channel: 'WEBHOOK',
          webhookId: 'fw_daily',
          webhookName: '娃屋日常机器人',
        }),
      }),
    )
    wrapper.unmount()
  })

  it('可新增多个补给项目并分别配置点击机制', async () => {
    vi.mocked(saveWorkdayRhythm).mockImplementation(async (value) => cloneRhythmConfig(value))
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.settings-button').trigger('click')
    await wrapper.get('.energy-items-editor > header button').trigger('click')
    const rows = wrapper.findAll('.energy-item-editor')
    expect(rows).toHaveLength(3)
    await rows[2]!.get('.energy-name-field input').setValue('起身走动')
    await rows[2]!.get('.energy-guidance-field input').setValue('离开座位活动几分钟')
    await rows[2]!.get('.energy-mode-field select').setValue('DAILY_ONCE')
    await wrapper.get('.editor .primary').trigger('click')
    await flushPromises()

    expect(saveWorkdayRhythm).toHaveBeenCalledWith(
      expect.objectContaining({
        energyItems: expect.arrayContaining([
          expect.objectContaining({
            title: '起身走动',
            guidance: '离开座位活动几分钟',
            mode: 'DAILY_ONCE',
          }),
        ]),
      }),
    )
    expect(wrapper.findAll('.energy-action')).toHaveLength(3)
    wrapper.unmount()
  })

  it('删除补给配置后再次保存仍保留当天既有次数和名称快照', async () => {
    const configured = cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM)
    configured.energyItems.push({
      id: 'WALK',
      title: '起身走动',
      guidance: '离开座位活动几分钟',
      mode: 'COUNT',
      notify: false,
    })
    const storedDay = defaultRhythmDay(clock.date, clock.weekday)
    storedDay.energyRecords.WALK = 3
    storedDay.energySnapshot.WALK = { title: '起身走动', mode: 'COUNT' }
    vi.mocked(fetchWorkdayRhythm).mockResolvedValue(configured)
    vi.mocked(fetchWorkdayRhythmDay).mockResolvedValue(storedDay)
    vi.mocked(saveWorkdayRhythm).mockImplementationOnce(async (value) => cloneRhythmConfig(value))
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.settings-button').trigger('click')
    await wrapper.findAll('.remove-energy-item')[2]!.trigger('click')
    await wrapper.get('.editor .primary').trigger('click')
    await flushPromises()
    await wrapper.findAll('.focus-card input')[0]!.setValue('保存其它真实目标')
    await wrapper.get('.focus-card footer button').trigger('click')
    await flushPromises()

    expect(saveWorkdayRhythmDay).toHaveBeenLastCalledWith(
      expect.objectContaining({
        energyRecords: expect.objectContaining({ WALK: 3 }),
        energySnapshot: expect.objectContaining({ WALK: { title: '起身走动', mode: 'COUNT' } }),
      }),
    )
    wrapper.unmount()
  })

  it('每天一次项目成功后禁用，累计项目可连续增加', async () => {
    const configured = cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM)
    configured.energyItems.push({
      id: 'WALK',
      title: '起身走动',
      guidance: '离开座位活动几分钟',
      mode: 'DAILY_ONCE',
      notify: false,
    })
    vi.mocked(fetchWorkdayRhythm).mockResolvedValue(configured)
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('.energy-action.water').trigger('click')
    await flushPromises()
    await wrapper.get('.energy-action.water').trigger('click')
    await flushPromises()
    expect(saveWorkdayRhythmDay).toHaveBeenLastCalledWith(
      expect.objectContaining({ energyRecords: expect.objectContaining({ WATER: 2 }) }),
    )

    const once = wrapper.findAll('.energy-action')[2]!
    await once.trigger('click')
    await flushPromises()
    expect(once.attributes('disabled')).toBeDefined()
    expect(once.text()).toContain('今天已完成')
    expect(saveWorkdayRhythmDay).toHaveBeenLastCalledWith(
      expect.objectContaining({ energyRecords: expect.objectContaining({ WALK: 1 }) }),
    )
    wrapper.unmount()
  })
})
