import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import WorkdayRhythmView from '@/views/WorkdayRhythmView.vue'
import {
  fetchWorkdayRhythmHistory,
  fetchWorkdayRhythm,
  fetchWorkdayRhythmDay,
  saveWorkdayRhythm,
  saveWorkdayRhythmDay,
  sendEnergyNotification,
  sendFocusNotification,
  saveRhythmFocus,
  setRhythmWorkdayStatus,
} from '@/api/workdayRhythm'
import { fetchFeishuChats, fetchWebhookEndpoints } from '@/api/system'
import { fetchDailyGuidance, generateDailyGuidance } from '@/api/dailyGuidance'
import { apiAddFavorite, apiRemoveFavorite, fetchFavorites } from '@/api/favorites'
import { useUiStore } from '@/stores/ui'
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
  sendFocusNotification: vi.fn<typeof sendFocusNotification>(),
  saveRhythmFocus: vi.fn<typeof saveRhythmFocus>(),
  setRhythmWorkdayStatus: vi.fn<typeof setRhythmWorkdayStatus>(),
}))

vi.mock('@/api/system', () => ({
  fetchFeishuChats: vi.fn<typeof fetchFeishuChats>(),
  fetchWebhookEndpoints: vi.fn<typeof fetchWebhookEndpoints>(),
}))

vi.mock('@/api/dailyGuidance', () => ({
  fetchDailyGuidance: vi.fn<typeof fetchDailyGuidance>(),
  generateDailyGuidance: vi.fn<typeof generateDailyGuidance>(),
}))

vi.mock('@/api/favorites', () => ({
  fetchFavorites: vi.fn<typeof fetchFavorites>(),
  apiAddFavorite: vi.fn<typeof apiAddFavorite>(),
  apiRemoveFavorite: vi.fn<typeof apiRemoveFavorite>(),
}))

const clock = shanghaiClock()
const tomorrow = (() => {
  const [year, month, date] = clock.date.split('-').map(Number)
  return new Date(Date.UTC(year!, month! - 1, date! + 1)).toISOString().slice(0, 10)
})()

async function mountView(path = '/workday-rhythm') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/workday-rhythm', component: WorkdayRhythmView },
      { path: '/todo', component: { template: '<div />' } },
      { path: '/tianren', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  return mount(WorkdayRhythmView, {
    global: {
      plugins: [createPinia(), router],
      stubs: {
        WorkdayRhythmDailyPanel: true,
        WorkdayRhythmDailySettings: true,
        WorkdayRhythmHexagramSummary: true,
      },
    },
  })
}

describe('WorkdayRhythmView', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
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
    vi.mocked(fetchWorkdayRhythmDay).mockImplementation(async (date, weekday) =>
      defaultRhythmDay(date, weekday),
    )
    vi.mocked(saveWorkdayRhythmDay).mockResolvedValue()
    vi.mocked(saveWorkdayRhythm).mockResolvedValue(cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM))
    vi.mocked(sendEnergyNotification).mockResolvedValue({ sent: true, chatName: '娃屋日常' })
    vi.mocked(sendFocusNotification).mockResolvedValue({ sent: true, chatName: '娃屋日常' })
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
    vi.mocked(fetchFavorites).mockResolvedValue([])
    vi.mocked(apiAddFavorite).mockResolvedValue()
    vi.mocked(apiRemoveFavorite).mockResolvedValue()
    vi.mocked(fetchDailyGuidance).mockResolvedValue({
      date: clock.date,
      status: 'ready',
      content:
        '## 今天看到的成长信号\n\n围绕「完成上线链路验收」，你正在从实现走向完整交付。\n\n## 换个角度\n\n先定义验收证据。\n\n## 问问自己\n\n> 哪个判断最影响今天的结果？\n\n## 今天做个小实验\n\n记录一次判断及结果。',
      message: '已结合今日重点与真实 Task 生成',
      generatedAt: '2026-08-31T08:00:00',
    })
    vi.mocked(generateDailyGuidance).mockResolvedValue({
      date: clock.date,
      status: 'ready',
      content: '## 今天看到的成长信号\n\n换一个融合今日重点的真实视角。',
      message: '已结合今日重点与真实 Task 生成',
      generatedAt: '2026-08-31T09:00:00',
    })
  })
  afterEach(() => vi.clearAllMocks())

  it('把完整真实模板收进顶部指导列表且不再提供时间块打卡', async () => {
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.findAll('.hero-flow li')).toHaveLength(7)
    expect(wrapper.text()).toContain('重点攻坚')
    expect(wrapper.find('.timeline-card').exists()).toBe(false)
    expect(wrapper.find('.time-block').exists()).toBe(false)
    expect(saveWorkdayRhythmDay).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('从宠物设置入口直达按账号保存的快捷操作编辑区', async () => {
    const wrapper = await mountView('/workday-rhythm?settings=pet')
    await flushPromises()

    expect(wrapper.find('.editor').exists()).toBe(true)
    expect(wrapper.vm.$route.query.settings).toBeUndefined()
    expect(wrapper.get('[data-editor-section="energy"]').classes()).toContain('active')
    expect(wrapper.get('#energy-items-title').text()).toBe('精力补给项目')
    expect(wrapper.findAll('.energy-item-editor')).toHaveLength(
      DEFAULT_WORKDAY_RHYTHM.energyItems.length,
    )

    await wrapper.get('.editor [aria-label="关闭"]').trigger('click')
    await wrapper.vm.$router.push({ query: { settings: 'pet' } })
    await flushPromises()

    expect(wrapper.find('.editor').exists()).toBe(true)
    expect(wrapper.vm.$route.query.settings).toBeUndefined()
    wrapper.unmount()
  })

  it('把本人填写的今日重点持久化而不生成任务数据', async () => {
    const wrapper = await mountView()
    await flushPromises()
    const inputs = wrapper.findAll('.focus-card input')
    await inputs[0]!.setValue('完成上线链路验收')
    const saveButton = wrapper.get('.focus-save')
    expect(saveButton.text()).toBe('')
    expect(saveButton.get('i').classes()).toContain('ri-save-3-line')
    expect(saveButton.attributes('aria-label')).toContain('草稿已保存在本机')
    await saveButton.trigger('click')
    await flushPromises()

    expect(saveWorkdayRhythmDay).toHaveBeenCalledWith(
      expect.objectContaining({
        focus: ['完成上线链路验收', '', ''],
      }),
    )
    expect(wrapper.text()).toContain('已保存到你的 WAO 账号')
    wrapper.unmount()
  })

  it('只在今日重点旁按需打开成长教练并展示融合后的真实结果', async () => {
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.find('.growth-coach-trigger').text()).toContain('成长教练')
    expect(wrapper.find('.growth-coach-dialog').exists()).toBe(false)
    expect(fetchDailyGuidance).not.toHaveBeenCalled()

    await wrapper.get('.growth-coach-trigger').trigger('click')
    await flushPromises()

    expect(fetchDailyGuidance).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.growth-coach-dialog').text()).toContain('从今天要完成的事，再想深一步')
    expect(wrapper.get('.growth-coach-content').text()).toContain('完成上线链路验收')
    expect(wrapper.get('.growth-coach-dialog').text()).toContain('已融合当时保存的今日重点')
    wrapper.unmount()
  })

  it('成长教练支持换角度并把可编辑问题带入 WAO AI', async () => {
    const wrapper = await mountView()
    await flushPromises()
    await wrapper.get('.growth-coach-trigger').trigger('click')
    await flushPromises()

    await wrapper.get('.growth-coach-refresh').trigger('click')
    await flushPromises()
    expect(generateDailyGuidance).toHaveBeenCalledWith(true)
    expect(wrapper.get('.growth-coach-content').text()).toContain('融合今日重点的真实视角')

    await wrapper.get('.growth-coach-discuss').trigger('click')
    const ui = useUiStore()
    expect(wrapper.find('.growth-coach-dialog').exists()).toBe(false)
    expect(ui.assistantOpen).toBe(true)
    expect(ui.assistantDraft).toContain('真实今日重点')
    expect(ui.assistantDraft).toContain('融合今日重点的真实视角')
    wrapper.unmount()
  })

  it('重点变化使后台结果待生成时保留重新读取和立即生成入口', async () => {
    vi.mocked(fetchDailyGuidance).mockResolvedValueOnce({
      date: clock.date,
      status: 'missing',
      content: null,
      message: '今日重点已变化，请按需生成新的成长启发',
      generatedAt: null,
    })
    const wrapper = await mountView()
    await flushPromises()

    await wrapper.get('.growth-coach-trigger').trigger('click')
    await flushPromises()
    expect(wrapper.get('.growth-coach-state').text()).toContain('今天的成长启发等待生成')
    expect(wrapper.get('.growth-coach-state').text()).toContain('现在生成')

    await wrapper.get('.growth-coach-state .primary').trigger('click')
    await flushPromises()
    expect(generateDailyGuidance).toHaveBeenCalledWith(false)
    expect(wrapper.get('.growth-coach-content').text()).toContain('融合今日重点的真实视角')
    wrapper.unmount()
  })

  it('列满三个重点后发群，并只允许按顺序推进当前项', async () => {
    const configured = cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM)
    configured.energyNotification = {
      enabled: true,
      notifyFocus: true,
      channel: 'CHAT',
      webhookId: '',
      webhookName: '',
      chatId: 'oc_daily',
      chatName: '娃屋日常',
    }
    vi.mocked(fetchWorkdayRhythm).mockResolvedValue(configured)
    const wrapper = await mountView()
    await flushPromises()

    const inputs = wrapper.findAll('.focus-row input')
    await inputs[0]!.setValue('交付最小 MVP')
    await inputs[1]!.setValue('完成上线验收')
    await inputs[2]!.setValue('整理真实反馈')
    await wrapper.get('.focus-save').trigger('click')
    await flushPromises()

    expect(sendFocusNotification).toHaveBeenCalledWith(clock.date)
    expect(wrapper.get('.focus-notice.sent').text()).toContain('已发到群里')
    expect(wrapper.findAll('.focus-complete')).toHaveLength(1)
    expect(wrapper.findAll('.focus-row')[0]!.classes()).toContain('active')

    await wrapper.get('.focus-complete').trigger('click')
    await flushPromises()
    expect(saveWorkdayRhythmDay).toHaveBeenLastCalledWith(
      expect.objectContaining({ focusCompleted: [true, false, false] }),
    )
    expect(wrapper.findAll('.focus-row')[1]!.classes()).toContain('active')
    vi.mocked(saveWorkdayRhythmDay).mockRejectedValueOnce(new Error('网络中断'))
    await wrapper.get('.focus-undo').trigger('click')
    await flushPromises()
    expect(wrapper.get('.focus-undo-retry').text()).toContain('重试')
    await wrapper.get('.focus-undo-retry').trigger('click')
    await flushPromises()
    expect(saveWorkdayRhythmDay).toHaveBeenLastCalledWith(
      expect.objectContaining({ focusCompleted: [false, false, false] }),
    )
    expect(wrapper.findAll('.focus-row')[0]!.classes()).toContain('active')
    expect(sendFocusNotification).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('默认保留三个重点并把追加项完整保存到服务端', async () => {
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.findAll('.focus-row')).toHaveLength(3)
    await wrapper.get('.focus-add').trigger('click')
    const inputs = wrapper.findAll('.focus-row input')
    expect(inputs).toHaveLength(4)
    await inputs[0]!.setValue('重点一')
    await inputs[1]!.setValue('重点二')
    await inputs[2]!.setValue('重点三')
    await inputs[3]!.setValue('临时插入事项')
    await wrapper.get('.focus-save').trigger('click')
    await flushPromises()

    expect(saveRhythmFocus).toHaveBeenCalledWith(clock.date, [
      '重点一',
      '重点二',
      '重点三',
      '临时插入事项',
    ])
    expect(saveWorkdayRhythmDay).toHaveBeenCalledWith(
      expect.objectContaining({ focusCompleted: [false, false, false, false] }),
    )
    wrapper.unmount()
  })

  it('移除三张执行卡的独立入口和冗余文案，并从顶部统一配置通知', async () => {
    vi.mocked(saveWorkdayRhythm).mockImplementation(async (value) => cloneRhythmConfig(value))
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.find('.notification-target-trigger').exists()).toBe(false)
    expect(wrapper.find('.energy-card header button').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('打开真实待办')
    expect(wrapper.text()).not.toContain('草稿已保存在本机')
    expect(wrapper.text()).not.toContain('新增重点')
    expect(wrapper.text()).not.toContain('累计项目可反复记录')

    await wrapper.get('.settings-button').trigger('click')
    expect(wrapper.get('[data-editor-section="checklist"]').classes()).toContain('active')
    await wrapper.get('[data-editor-section="notification"]').trigger('click')
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
          notifyFocus: true,
          channel: 'CHAT',
          chatId: 'oc_daily',
          chatName: '娃屋日常',
        }),
      }),
    )

    const inputs = wrapper.findAll('.focus-row input')
    await inputs[0]!.setValue('交付选择器')
    await inputs[1]!.setValue('完成真实验收')
    await inputs[2]!.setValue('整理反馈')
    await wrapper.get('.focus-save').trigger('click')
    await flushPromises()

    expect(sendFocusNotification).toHaveBeenCalledWith(clock.date)
    wrapper.unmount()
  })

  it('统一配置保存失败时保留本机草稿并展示可恢复错误', async () => {
    const configured = cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM)
    configured.energyNotification = {
      enabled: true,
      notifyFocus: true,
      channel: 'CHAT',
      webhookId: '',
      webhookName: '',
      chatId: 'oc_daily',
      chatName: '娃屋日常',
    }
    vi.mocked(fetchWorkdayRhythm).mockResolvedValue(configured)
    vi.mocked(saveWorkdayRhythm).mockRejectedValueOnce(new Error('网络暂时不可用'))
    const wrapper = await mountView()
    await flushPromises()

    await wrapper.get('.settings-button').trigger('click')
    await wrapper.get('[data-editor-section="notification"]').trigger('click')
    await wrapper.get('.channel-field select').setValue('WEBHOOK')
    await flushPromises()
    await wrapper.get('.chat-field select').setValue('fw_daily')
    await wrapper.get('.editor .primary').trigger('click')
    await flushPromises()

    expect(wrapper.get('.editor-error').text()).toContain('网络暂时不可用')
    expect(localStorage.getItem('wao-workday-rhythm-config-draft-v1')).toContain('fw_daily')
    expect(wrapper.get('.chat-field select').element).toHaveProperty('value', 'fw_daily')
    wrapper.unmount()
  })

  it('重点通知失败不丢已保存目标，并可原位重试', async () => {
    const configured = cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM)
    configured.energyNotification.enabled = true
    configured.energyNotification.notifyFocus = true
    configured.energyNotification.channel = 'CHAT'
    configured.energyNotification.chatId = 'oc_daily'
    configured.energyNotification.chatName = '娃屋日常'
    vi.mocked(fetchWorkdayRhythm).mockResolvedValue(configured)
    vi.mocked(sendFocusNotification)
      .mockRejectedValueOnce(new Error('飞书暂时不可用'))
      .mockResolvedValueOnce({ sent: true, chatName: '娃屋日常' })
    const wrapper = await mountView()
    await flushPromises()

    const inputs = wrapper.findAll('.focus-row input')
    await inputs[0]!.setValue('重点一')
    await inputs[1]!.setValue('重点二')
    await inputs[2]!.setValue('重点三')
    await wrapper.get('.focus-save').trigger('click')
    await flushPromises()

    expect(saveWorkdayRhythmDay).toHaveBeenCalled()
    expect(wrapper.get('.focus-notice.error').text()).toContain('群通知未发出')
    await wrapper.get('.focus-notice.error button').trigger('click')
    await flushPromises()
    expect(sendFocusNotification).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.focus-notice.sent').text()).toContain('已发到群里')
    wrapper.unmount()
  })

  it('提前保存明日重点并在保存成功前持续保留本机草稿', async () => {
    const wrapper = await mountView()
    await flushPromises()
    expect(wrapper.find('.tomorrow-focus').exists()).toBe(false)
    await wrapper.get('.tomorrow-toggle').trigger('click')

    const input = wrapper.find('.tomorrow-focus input')
    await input.setValue('为紧急评审准备验收证据')
    expect(localStorage.getItem(`wao-workday-rhythm-day-draft-${tomorrow}`)).toContain(
      '为紧急评审准备验收证据',
    )

    await wrapper.get('.tomorrow-focus footer button').trigger('click')
    await flushPromises()

    expect(saveRhythmFocus).toHaveBeenCalledWith(tomorrow, ['为紧急评审准备验收证据', '', ''])
    expect(localStorage.getItem(`wao-workday-rhythm-day-draft-${tomorrow}`)).toBeNull()
    expect(wrapper.get('.tomorrow-focus').text()).toContain('已保存，明天自动接续')
    wrapper.unmount()
  })

  it('明日计划保存失败时保留输入并提供原位重试', async () => {
    vi.mocked(saveRhythmFocus).mockRejectedValueOnce(new Error('网络暂时不可用'))
    const wrapper = await mountView()
    await flushPromises()
    await wrapper.get('.tomorrow-toggle').trigger('click')

    await wrapper.find('.tomorrow-focus input').setValue('准备明天的发布清单')
    await wrapper.get('.tomorrow-focus footer button').trigger('click')
    await flushPromises()

    expect(wrapper.get('.tomorrow-focus').text()).toContain('网络暂时不可用，草稿仍在本机')
    expect(localStorage.getItem(`wao-workday-rhythm-day-draft-${tomorrow}`)).toContain(
      '准备明天的发布清单',
    )

    await wrapper.get('.tomorrow-focus footer button').trigger('click')
    await flushPromises()
    expect(saveRhythmFocus).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.tomorrow-focus').text()).toContain('已保存，明天自动接续')
    wrapper.unmount()
  })

  it('移除单独打开和复制链接，并保留成员级收藏', async () => {
    const wrapper = await mountView()
    await flushPromises()

    expect(wrapper.text()).not.toContain('单独打开')
    expect(wrapper.text()).not.toContain('复制链接')
    const actions = wrapper.findAll('.page-action')
    await actions[0]!.trigger('click')
    await flushPromises()
    expect(apiAddFavorite).toHaveBeenCalledWith('module', 'workday-rhythm')
    expect(actions[0]!.text()).toContain('已收藏')
    wrapper.unmount()
  })

  it('从调整节奏旁按月回顾真实每日目标和项目执行次数', async () => {
    const wrapper = await mountView()
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
    const wrapper = await mountView()
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
    const wrapper = await mountView()
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
    const wrapper = await mountView()
    await flushPromises()
    await wrapper.get('.settings-button').trigger('click')
    await wrapper.get('[data-editor-section="timeline"]').trigger('click')
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

  it('从顶部统一入口设置本人通知群并随节奏配置跨设备保存', async () => {
    const wrapper = await mountView()
    await flushPromises()

    await wrapper.get('.settings-button').trigger('click')
    await flushPromises()
    await wrapper.get('[data-editor-section="notification"]').trigger('click')
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
      notifyFocus: true,
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
    const wrapper = await mountView()
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
    const wrapper = await mountView()
    await flushPromises()

    await wrapper.get('.settings-button').trigger('click')
    await wrapper.get('[data-editor-section="notification"]').trigger('click')
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
    const wrapper = await mountView()
    await flushPromises()

    await wrapper.get('.settings-button').trigger('click')
    await wrapper.get('[data-editor-section="energy"]').trigger('click')
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
    const wrapper = await mountView()
    await flushPromises()

    await wrapper.get('.settings-button').trigger('click')
    await wrapper.get('[data-editor-section="energy"]').trigger('click')
    await wrapper.findAll('.remove-energy-item')[2]!.trigger('click')
    await wrapper.get('.editor .primary').trigger('click')
    await flushPromises()
    await wrapper.findAll('.focus-card input')[0]!.setValue('保存其它真实目标')
    await wrapper.get('.focus-save').trigger('click')
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
    const wrapper = await mountView()
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
