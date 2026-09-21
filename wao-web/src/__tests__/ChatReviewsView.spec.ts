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
    sourceKind: 'FEISHU_ARCHIVE',
    rangeKind: null,
    rangeValue: null,
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
    reportUrl: null,
    knowledgeUrl: null,
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

  it('停用飞书同步入口，保留历史报告', async () => {
    const wrapper = await mountView()
    expect(wrapper.text()).toContain('飞书聊天同步已停用')
    expect(wrapper.find('.request-card').exists()).toBe(false)
    expect(wrapper.find('.config-card').exists()).toBe(false)
    expect(createChatReview).not.toHaveBeenCalled()
    expect(rerunChatReview).not.toHaveBeenCalled()
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
