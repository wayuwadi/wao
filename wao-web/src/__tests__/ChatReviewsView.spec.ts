import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ChatReviewsView from '@/views/ChatReviewsView.vue'
import {
  createChatReview,
  fetchChatReview,
  fetchChatReviewConfig,
  fetchChatReviews,
  rerunChatReview,
  retryChatReview,
  saveChatReviewConfig,
  type ChatReviewConfig,
  type ChatReviewRunDetail,
  type ChatReviewRunSummary,
} from '@/api/chatReviews'
import { copyText } from '@/utils/clipboard'

vi.mock('@/api/chatReviews', () => ({
  createChatReview: vi.fn<(typeof import('@/api/chatReviews'))['createChatReview']>(),
  fetchChatReview: vi.fn<(typeof import('@/api/chatReviews'))['fetchChatReview']>(),
  fetchChatReviewConfig: vi.fn<(typeof import('@/api/chatReviews'))['fetchChatReviewConfig']>(),
  fetchChatReviews: vi.fn<(typeof import('@/api/chatReviews'))['fetchChatReviews']>(),
  rerunChatReview: vi.fn<(typeof import('@/api/chatReviews'))['rerunChatReview']>(),
  retryChatReview: vi.fn<(typeof import('@/api/chatReviews'))['retryChatReview']>(),
  saveChatReviewConfig: vi.fn<(typeof import('@/api/chatReviews'))['saveChatReviewConfig']>(),
}))
vi.mock('@/utils/clipboard', () => ({
  copyText: vi.fn<(typeof import('@/utils/clipboard'))['copyText']>().mockResolvedValue(undefined),
}))
vi.mock('@/composables/toast', () => ({
  toast: vi.fn<(typeof import('@/composables/toast'))['toast']>(),
}))

const configured: ChatReviewConfig = {
  chatConfigured: true,
  configured: true,
  chatId: 'oc-chat',
  chatName: '娃屋日常',
  availableSpeakers: [
    { id: 'ou-brother', name: '娃哥' },
    { id: 'ou-younger', name: '娃弟' },
    { id: 'ou-helper', name: '其他成员' },
  ],
  selectedSpeakers: [
    { id: 'ou-brother', name: '娃哥' },
    { id: 'ou-younger', name: '娃弟' },
  ],
  message: '已从当前飞书同步配置中固定两位发言人',
}

const report = {
  discussionProcess: '先梳理问题，再比较两种方案 [S1]',
  discussionDirections: '讨论围绕可靠性与上线节奏 [S1]',
  conclusions: '已明确：先补回归验证 [S1]\n\n未解决：上线日期。',
  recommendations: 'AI 建议：为未解决项设置截止时间。',
}

function summary(
  id: string,
  stage: ChatReviewRunSummary['stage'] = 'SUCCEEDED',
  overrides: Partial<ChatReviewRunSummary> = {},
): ChatReviewRunSummary {
  return {
    id,
    startDate: '2026-08-13',
    endDate: '2026-08-19',
    focus: '关注上线节奏',
    stage,
    progressCurrent: stage === 'SYNCING' ? 3 : 7,
    progressTotal: 7,
    currentDate: stage === 'SYNCING' ? '2026-08-15' : null,
    failedStage: stage === 'FAILED' ? 'SYNCING' : null,
    failedDate: stage === 'FAILED' ? '2026-08-15' : null,
    error: stage === 'FAILED' ? '2026-08-15 同步失败' : null,
    messageCount: 42,
    createdById: 'm1',
    createdByName: '娃哥',
    createdAt: '2026-08-19T10:00:00+08:00',
    startedAt: '2026-08-19T10:00:01+08:00',
    finishedAt: ['SUCCEEDED', 'FAILED'].includes(stage) ? '2026-08-19T10:05:00+08:00' : null,
    ...overrides,
  }
}

function detail(
  id: string,
  stage: ChatReviewRunSummary['stage'] = 'SUCCEEDED',
  overrides: Partial<ChatReviewRunDetail> = {},
): ChatReviewRunDetail {
  return {
    ...summary(id, stage),
    chatId: 'oc-chat',
    chatName: '娃屋日常',
    speakers: configured.selectedSpeakers,
    aiRunId: stage === 'SUCCEEDED' ? 'ai-1' : null,
    sources:
      stage === 'SUCCEEDED'
        ? [
            {
              id: 'S1',
              date: '2026-08-15',
              kbId: 'kb-chat',
              documentId: 'doc-1',
              url: '/kb/kb-chat?doc=doc-1',
              messageCount: 42,
            },
          ]
        : [],
    report: stage === 'SUCCEEDED' ? report : null,
    ...overrides,
  }
}

let wrappers: VueWrapper[] = []

async function mountView() {
  const wrapper = mount(ChatReviewsView, {
    global: {
      stubs: {
        RouterLink: { template: '<a><slot /></a>' },
      },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}

describe('ChatReviewsView', () => {
  beforeEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(fetchChatReviewConfig).mockResolvedValue(configured)
    vi.mocked(saveChatReviewConfig).mockResolvedValue(configured)
    vi.mocked(fetchChatReviews).mockResolvedValue({ activeRun: null, history: [] })
    vi.mocked(fetchChatReview).mockImplementation(async (id) => detail(id))
    vi.mocked(createChatReview).mockResolvedValue({ runId: 'run-new' })
    vi.mocked(rerunChatReview).mockResolvedValue({ runId: 'run-rerun' })
    vi.mocked(retryChatReview).mockResolvedValue({ runId: 'run-retry' })
    vi.mocked(copyText).mockResolvedValue(undefined)
  })

  afterEach(() => {
    for (const wrapper of wrappers) wrapper.unmount()
    wrappers = []
    vi.useRealTimers()
  })

  it('未配置时先固定两位不同发言人', async () => {
    const missing = { ...configured, configured: false, selectedSpeakers: [] }
    vi.mocked(fetchChatReviewConfig).mockResolvedValue(missing)
    vi.mocked(saveChatReviewConfig).mockResolvedValue(configured)
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('先选择两位分析对象')
    expect(
      wrapper.get<HTMLButtonElement>('.request-actions .primary-button').element.disabled,
    ).toBe(true)
    const selects = wrapper.findAll('.speaker-editor select')
    await selects[0]!.setValue('ou-brother')
    await selects[1]!.setValue('ou-younger')
    await wrapper.get('.speaker-editor .primary-button').trigger('click')
    await flushPromises()

    expect(saveChatReviewConfig).toHaveBeenCalledWith(['ou-brother', 'ou-younger'])
    expect(wrapper.text()).toContain('已固定两位分析对象')
  })

  it('从按成员隔离的本机草稿恢复表单', async () => {
    localStorage.setItem(
      'wao-chat-review-draft-v1:unknown',
      JSON.stringify({
        startDate: '2026-08-01',
        endDate: '2026-08-07',
        focus: '草稿重点',
        updatedAt: '2026-08-19T09:00:00Z',
      }),
    )
    const wrapper = await mountView()
    const dates = wrapper.findAll<HTMLInputElement>('.date-grid input')

    expect(dates[0]!.element.value).toBe('2026-08-01')
    expect(dates[1]!.element.value).toBe('2026-08-07')
    expect(wrapper.get<HTMLTextAreaElement>('.focus-field textarea').element.value).toBe('草稿重点')
    expect(wrapper.text()).toContain('草稿已保存')
  })

  it('严格提交日期和可选 focus，连续点击不重复入队', async () => {
    let resolveCreate!: (value: { runId: string }) => void
    vi.mocked(createChatReview).mockReturnValue(
      new Promise((resolve) => {
        resolveCreate = resolve
      }),
    )
    const wrapper = await mountView()
    const dates = wrapper.findAll('.date-grid input')
    await dates[0]!.setValue('2026-08-13')
    await dates[1]!.setValue('2026-08-19')
    await wrapper.get('.focus-field textarea').setValue('  只关注结论  ')
    const submit = wrapper.get('.request-actions .primary-button')

    await submit.trigger('click')
    await submit.trigger('click')
    expect(createChatReview).toHaveBeenCalledTimes(1)
    expect(createChatReview).toHaveBeenCalledWith({
      startDate: '2026-08-13',
      endDate: '2026-08-19',
      focus: '只关注结论',
    })

    resolveCreate({ runId: 'run-new' })
    await flushPromises()
    expect(wrapper.text()).toContain('实时运行')
  })

  it('轮询更新运行但不覆盖尚未提交的重点，卸载后停止', async () => {
    vi.useFakeTimers()
    const running = summary('run-active', 'WAITING')
    vi.mocked(fetchChatReviews).mockResolvedValue({ activeRun: running, history: [] })
    vi.mocked(fetchChatReview)
      .mockResolvedValueOnce(detail('run-active', 'SYNCING', { progressCurrent: 4 }))
      .mockResolvedValue(detail('run-active', 'ANALYZING'))
    const wrapper = await mountView()
    await wrapper.get('.focus-field textarea').setValue('这是我正在填的下一次重点')

    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    expect(wrapper.text()).toContain('已处理 4 / 7 天')
    expect(wrapper.get<HTMLTextAreaElement>('.focus-field textarea').element.value).toBe(
      '这是我正在填的下一次重点',
    )
    const calls = vi.mocked(fetchChatReview).mock.calls.length

    wrapper.unmount()
    wrappers = wrappers.filter((item) => item !== wrapper)
    await vi.advanceTimersByTimeAsync(10_000)
    expect(fetchChatReview).toHaveBeenCalledTimes(calls)
  })

  it('新报告完成时不强制切走正在阅读的历史报告', async () => {
    vi.useFakeTimers()
    const oldRun = summary('run-old')
    const active = summary('run-active', 'WAITING')
    vi.mocked(fetchChatReviews)
      .mockResolvedValueOnce({ activeRun: active, history: [oldRun] })
      .mockResolvedValueOnce({ activeRun: null, history: [summary('run-active'), oldRun] })
    vi.mocked(fetchChatReview).mockImplementation(async (id) =>
      id === 'run-old'
        ? detail('run-old', 'SUCCEEDED', {
            report: { ...report, discussionProcess: '旧报告过程 [S1]' },
          })
        : detail('run-active', 'SUCCEEDED', {
            report: { ...report, discussionProcess: '新报告过程 [S1]' },
          }),
    )
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('旧报告过程')

    await vi.advanceTimersByTimeAsync(5_000)
    await flushPromises()
    expect(wrapper.text()).toContain('旧报告过程')
    expect(wrapper.text()).toContain('新复盘已保存')

    await wrapper.get('.new-result button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('新报告过程')
  })

  it('失败详情显示准确日期，重试走专用接口', async () => {
    const failed = summary('run-failed', 'FAILED')
    vi.mocked(fetchChatReviews).mockResolvedValue({ activeRun: null, history: [failed] })
    vi.mocked(fetchChatReview).mockImplementation(async (id) =>
      id === 'run-failed' ? detail(id, 'FAILED') : detail(id, 'WAITING'),
    )
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('2026-08-15 处理失败')
    expect(wrapper.text()).toContain('2026-08-15 同步失败')
    await wrapper.get('.failed-detail button').trigger('click')
    await flushPromises()

    expect(retryChatReview).toHaveBeenCalledWith('run-failed')
    expect(createChatReview).not.toHaveBeenCalled()
  })

  it('成功报告按完整快照重新运行，不用当前配置重建参数', async () => {
    const completed = summary('run-completed')
    vi.mocked(fetchChatReviews).mockResolvedValue({ activeRun: null, history: [completed] })
    vi.mocked(fetchChatReview).mockImplementation(async (id) =>
      id === 'run-completed' ? detail(id) : detail(id, 'WAITING'),
    )
    const wrapper = await mountView()

    await wrapper.get('.report-actions button:last-child').trigger('click')
    await flushPromises()

    expect(rerunChatReview).toHaveBeenCalledWith('run-completed')
    expect(createChatReview).not.toHaveBeenCalled()
  })

  it('复制四段 Markdown 时带上可回溯的来源附录', async () => {
    const completed = summary('run-completed')
    vi.mocked(fetchChatReviews).mockResolvedValue({ activeRun: null, history: [completed] })
    vi.mocked(fetchChatReview).mockResolvedValue(detail('run-completed'))
    const wrapper = await mountView()

    expect(wrapper.findAll('.report-sections article')).toHaveLength(4)
    expect(wrapper.get('.source-list a').attributes('href')).toBe('/kb/kb-chat?doc=doc-1')
    await wrapper.get('.report-actions button').trigger('click')

    expect(copyText).toHaveBeenCalledWith(
      expect.stringContaining('## 讨论过程'),
      '复盘 Markdown 已复制',
    )
    const markdown = vi.mocked(copyText).mock.calls[0]![0]
    expect(markdown).toContain('## 后续建议')
    expect(markdown).toContain('## 证据来源')
    expect(markdown).toContain('[打开原文](/kb/kb-chat?doc=doc-1)')
  })

  it('报告字段残缺时不伪装成成功内容', async () => {
    const completed = summary('run-invalid')
    vi.mocked(fetchChatReviews).mockResolvedValue({ activeRun: null, history: [completed] })
    vi.mocked(fetchChatReview).mockResolvedValue(
      detail('run-invalid', 'SUCCEEDED', {
        report: { ...report, conclusions: '' },
      }),
    )
    const wrapper = await mountView()

    expect(wrapper.find('.report-sections').exists()).toBe(false)
    expect(wrapper.text()).toContain('报告字段不完整')
    expect(wrapper.find('.copy-button').exists()).toBe(false)
  })
})
