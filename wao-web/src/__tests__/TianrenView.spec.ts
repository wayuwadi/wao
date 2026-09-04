import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import TianrenView from '@/views/TianrenView.vue'
vi.mock('@/components/workday/TianrenNotificationStatus.vue', () => ({ default: { template: '<div />' } }))
import {
  createTodayTianrenReading,
  createTianrenShareLink,
  fetchTianrenHistory,
  fetchTodayTianrenReading,
  streamTianrenInterpretation,
  type TianrenReading,
} from '@/api/tianren'
import { fetchAssistantConversation, fetchAssistantStatus } from '@/api/assistant'

vi.mock('@/api/tianren', () => ({
  createTodayTianrenReading: vi.fn<(typeof import('@/api/tianren'))['createTodayTianrenReading']>(),
  createTianrenShareLink: vi.fn<(typeof import('@/api/tianren'))['createTianrenShareLink']>(),
  fetchTianrenHistory: vi.fn<(typeof import('@/api/tianren'))['fetchTianrenHistory']>(),
  fetchTodayTianrenReading: vi.fn<(typeof import('@/api/tianren'))['fetchTodayTianrenReading']>(),
  streamTianrenInterpretation: vi.fn<(typeof import('@/api/tianren'))['streamTianrenInterpretation']>(),
}))

vi.mock('@/utils/clipboard', () => ({
  copyToClipboard: vi.fn().mockResolvedValue(true),
}))

vi.mock('@/api/assistant', () => ({
  fetchAssistantStatus: vi.fn<(typeof import('@/api/assistant'))['fetchAssistantStatus']>(),
  fetchAssistantConversation: vi.fn<(typeof import('@/api/assistant'))['fetchAssistantConversation']>(),
}))

const reading: TianrenReading = {
  id: 'tr1',
  date: new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date()),
  lines: [9, 7, 7, 8, 8, 8],
  changingLines: [1],
  primary: {
    number: 11, name: '泰', symbol: '䷊',
    upper: { name: '坤', nature: '地', symbol: '☷' },
    lower: { name: '乾', nature: '天', symbol: '☰' },
  },
  relating: {
    number: 46, name: '升', symbol: '䷭',
    upper: { name: '坤', nature: '地', symbol: '☷' },
    lower: { name: '巽', nature: '风', symbol: '☴' },
  },
  conversationId: null,
  createdAt: '2026-08-11T10:00:00',
}

describe('TianrenView', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(fetchAssistantStatus).mockResolvedValue({ configured: true, message: '已连接本地 Codex' })
    vi.mocked(fetchTianrenHistory).mockResolvedValue([])
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue(null)
    vi.mocked(streamTianrenInterpretation).mockImplementation(async function* () {})
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('今日为空时由用户主动起卦并自动生成首次解读', async () => {
    vi.mocked(createTodayTianrenReading).mockResolvedValue(reading)
    vi.mocked(fetchTianrenHistory).mockResolvedValueOnce([]).mockResolvedValue([reading])
    vi.mocked(fetchTodayTianrenReading).mockResolvedValueOnce(null).mockResolvedValueOnce(reading)
    vi.mocked(streamTianrenInterpretation).mockImplementation(async function* () {
      yield '## 自动解读\n稳住今天的节奏。'
    })
    const wrapper = mount(TianrenView)
    await flushPromises()

    expect(wrapper.text()).toContain('今天还没有起卦')
    await wrapper.get('.cast-button').trigger('click')
    await flushPromises()

    expect(createTodayTianrenReading).toHaveBeenCalledTimes(1)
    expect(streamTianrenInterpretation).toHaveBeenCalledWith('')
    expect(wrapper.text()).toContain('第 11 卦 · 泰')
    expect(wrapper.text()).toContain('泰 → 升')
    expect(wrapper.text()).toContain('自动解读')
    expect(wrapper.text()).toContain('根据关注点重新解读')
    expect(wrapper.findAll('.yao-row')).toHaveLength(6)
  })

  it('自动解读失败时保留已保存卦象并提供重新解读入口', async () => {
    vi.mocked(createTodayTianrenReading).mockResolvedValue(reading)
    vi.mocked(fetchTianrenHistory).mockResolvedValueOnce([]).mockResolvedValueOnce([reading])
    vi.mocked(streamTianrenInterpretation).mockImplementation(async function* () {
      throw new Error('Runner 暂时离线')
    })
    const wrapper = mount(TianrenView)
    await flushPromises()

    await wrapper.get('.cast-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('第 11 卦 · 泰')
    expect(wrapper.text()).toContain('Runner 暂时离线')
    expect(wrapper.text()).toContain('保留关注点并重试')
  })

  it('起卦早于 Codex 状态返回时等待同一次检查并自动解读', async () => {
    let finishStatus!: (value: { configured: boolean, message: string }) => void
    vi.mocked(fetchAssistantStatus).mockReturnValue(new Promise((resolve) => {
      finishStatus = resolve
    }))
    vi.mocked(createTodayTianrenReading).mockResolvedValue(reading)
    vi.mocked(fetchTianrenHistory).mockResolvedValueOnce([]).mockResolvedValue([reading])
    const wrapper = mount(TianrenView)
    await flushPromises()

    await wrapper.get('.cast-button').trigger('click')
    expect(streamTianrenInterpretation).not.toHaveBeenCalled()

    finishStatus({ configured: true, message: '已连接本地 Codex' })
    await flushPromises()

    expect(fetchAssistantStatus).toHaveBeenCalledTimes(1)
    expect(streamTianrenInterpretation).toHaveBeenCalledWith('')
  })

  it('本地 Codex 离线时仍展示卦象但禁用 AI 解读', async () => {
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue(reading)
    vi.mocked(fetchTianrenHistory).mockResolvedValue([reading])
    vi.mocked(fetchAssistantStatus).mockResolvedValue({ configured: false, message: '未检测到在线 Codex' })
    const wrapper = mount(TianrenView)
    await flushPromises()

    expect(wrapper.text()).toContain('未检测到在线 Codex')
    expect(wrapper.get<HTMLButtonElement>('.interpret-button').element.disabled).toBe(true)
    expect(wrapper.text()).toContain('文化反思，不是医疗建议')
  })

  it('恢复今日成员私有会话中的既有 AI 解读', async () => {
    const withConversation = { ...reading, conversationId: 'cv1' }
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue(withConversation)
    vi.mocked(fetchTianrenHistory).mockResolvedValue([withConversation])
    vi.mocked(fetchAssistantConversation).mockResolvedValue({
      conversation: { id: 'cv1', title: '天人一卦', updatedAt: '2026-08-11T10:01:00' },
      messages: [
        { id: 'm1', role: 'user', content: '请解读', createdAt: '2026-08-11T10:00:00' },
        { id: 'm2', role: 'assistant', content: '## 今日卦意\n稳住节奏。', createdAt: '2026-08-11T10:01:00' },
      ],
    })
    const wrapper = mount(TianrenView)
    await flushPromises()

    expect(fetchAssistantConversation).toHaveBeenCalledWith('cv1')
    expect(wrapper.text()).toContain('今日卦意')
    expect(wrapper.text()).toContain('稳住节奏')
    expect(wrapper.text()).toContain('卦象事实')
    expect(wrapper.text()).toContain('传统观点 · AI 概括')
    expect(wrapper.text()).toContain('当日建议 · AI 延伸')
    expect(wrapper.find('.ai-mark').exists()).toBe(true)
  })

  it('点击历史日期恢复当日完整卦象和已保存的联合解读', async () => {
    const past = {
      ...reading,
      id: 'tr-old',
      date: '2026-08-10',
      conversationId: 'cv-old',
      primary: {
        number: 2, name: '坤', symbol: '䷁',
        upper: { name: '坤', nature: '地', symbol: '☷' },
        lower: { name: '坤', nature: '地', symbol: '☷' },
      },
      relating: null,
    }
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue(reading)
    vi.mocked(fetchTianrenHistory).mockResolvedValue([reading, past])
    vi.mocked(fetchAssistantConversation).mockResolvedValue({
      conversation: { id: 'cv-old', title: '天人一卦', updatedAt: '2026-08-10T10:01:00' },
      messages: [
        { id: 'm1', role: 'assistant', content: '## 《易经》卦意（AI 概括）\n以承载与顺势自照。', createdAt: '2026-08-10T10:01:00' },
      ],
    })
    const wrapper = mount(TianrenView)
    await flushPromises()

    await wrapper.findAll('.history-row')[1]!.trigger('click')
    await flushPromises()

    expect(fetchAssistantConversation).toHaveBeenCalledWith('cv-old')
    expect(wrapper.text()).toContain('历史卦象')
    expect(wrapper.text()).toContain('第 2 卦 · 坤')
    expect(wrapper.findAll('.yao-row')).toHaveLength(6)
    expect(wrapper.text()).toContain('以承载与顺势自照')
    expect(wrapper.find('textarea').exists()).toBe(false)
    expect(wrapper.find('.interpret-button').exists()).toBe(false)
    expect(streamTianrenInterpretation).not.toHaveBeenCalled()

    await wrapper.get('.today-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('今日卦象')
    expect(wrapper.find('textarea').exists()).toBe(true)
  })

  it('没有 AI 会话的历史日期只回看真实卦象且明确显示无解读存档', async () => {
    const past = { ...reading, id: 'tr-old', date: '2026-08-09', conversationId: null }
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue(reading)
    vi.mocked(fetchTianrenHistory).mockResolvedValue([reading, past])
    const wrapper = mount(TianrenView)
    await flushPromises()

    await wrapper.findAll('.history-row')[1]!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('这一天没有保存 AI 解读')
    expect(wrapper.text()).toContain('历史结果不会用今天的接口补做或伪造')
    expect(fetchAssistantConversation).not.toHaveBeenCalled()
    expect(streamTianrenInterpretation).not.toHaveBeenCalled()
  })

  it('AI 失败时保留本机关注点草稿并提供重试', async () => {
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue(reading)
    vi.mocked(fetchTianrenHistory).mockResolvedValue([reading])
    vi.mocked(streamTianrenInterpretation).mockImplementation(async function* () {
      throw new Error('Runner 暂时离线')
    })
    const wrapper = mount(TianrenView)
    await flushPromises()

    await wrapper.get('textarea').setValue('最近总在赶进度')
    await wrapper.get('.interpret-button').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('Runner 暂时离线')
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('最近总在赶进度')
    expect(localStorage.getItem(`wao-tianren-focus-v1:${reading.date}`)).toBe('最近总在赶进度')
    expect(wrapper.text()).toContain('保留关注点并重试')
  })

  it('起卦后生成公网结果页并复制链接', async () => {
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue(reading)
    vi.mocked(fetchTianrenHistory).mockResolvedValue([reading])
    vi.mocked(createTianrenShareLink).mockResolvedValue('https://wao.example/s/tianren/token1')
    const { copyToClipboard } = await import('@/utils/clipboard')
    const wrapper = mount(TianrenView)
    await flushPromises()

    await wrapper.get('.share-button').trigger('click')
    await flushPromises()

    expect(createTianrenShareLink).toHaveBeenCalledWith('tr1')
    expect(copyToClipboard).toHaveBeenCalledWith('https://wao.example/s/tianren/token1')
    expect(wrapper.text()).toContain('公网结果链接已复制')
  })

  it('浏览器拒绝复制时展示可手动选取的完整链接', async () => {
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue(reading)
    vi.mocked(fetchTianrenHistory).mockResolvedValue([reading])
    vi.mocked(createTianrenShareLink).mockResolvedValue('https://wao.example/s/tianren/token2')
    const { copyToClipboard } = await import('@/utils/clipboard')
    vi.mocked(copyToClipboard).mockResolvedValueOnce(false)
    const wrapper = mount(TianrenView)
    await flushPromises()

    await wrapper.get('.share-button').trigger('click')
    await flushPromises()

    expect(wrapper.get<HTMLInputElement>('.share-feedback input').element.value)
      .toBe('https://wao.example/s/tianren/token2')
    expect(wrapper.text()).toContain('请手动复制')
  })

  it('分享接口失败时保留当前卦象并提供错误反馈', async () => {
    vi.mocked(fetchTodayTianrenReading).mockResolvedValue(reading)
    vi.mocked(fetchTianrenHistory).mockResolvedValue([reading])
    vi.mocked(createTianrenShareLink).mockRejectedValue(new Error('分享服务暂不可用'))
    const wrapper = mount(TianrenView)
    await flushPromises()

    await wrapper.get('.share-button').trigger('click')
    await flushPromises()

    expect(wrapper.get('.share-feedback').attributes('role')).toBe('alert')
    expect(wrapper.get('.share-feedback').classes()).toContain('error')
    expect(wrapper.text()).toContain('分享服务暂不可用')
    expect(wrapper.text()).toContain('第 11 卦 · 泰')
  })
})
