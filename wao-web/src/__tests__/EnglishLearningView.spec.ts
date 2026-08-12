import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import EnglishLearningView from '@/views/EnglishLearningView.vue'
import {
  fetchEnglishLearning,
  saveEnglishLearningSession,
  type EnglishLearningProfile,
  type EnglishLearningOverview,
  type EnglishLearningSession,
  type SaveEnglishLearningSession,
} from '@/api/englishLearning'
import { captureVocabulary, type CaptureVocabularyResult } from '@/api/vocabulary'

vi.mock('@/api/englishLearning', () => ({
  fetchEnglishLearning: vi.fn<(date?: string) => Promise<EnglishLearningOverview>>(),
  saveEnglishLearningProfile: vi.fn<() => Promise<EnglishLearningProfile>>(),
  saveEnglishLearningSession: vi.fn<
    (date: string, body: SaveEnglishLearningSession) => Promise<EnglishLearningSession>
  >(),
}))
vi.mock('@/api/vocabulary', () => ({
  captureVocabulary: vi.fn<(term: string) => Promise<CaptureVocabularyResult>>(),
}))

const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
const overview = (session: EnglishLearningSession | null = null): EnglishLearningOverview => ({
  profile: { id: 'ep1', goal: '主动完成英文交流', dailyTargetMinutes: 25, startDate: today, updatedAt: `${today}T10:00:00` },
  selectedDate: today,
  session,
  completedDays: session?.completedAt ? 1 : 0,
  currentStreak: session?.completedAt ? 1 : 0,
  totalMinutes: session?.practiceMinutes ?? 0,
  recentSessions: session ? [session] : [],
})
const completedSession = (): EnglishLearningSession => ({
  id: 'es1', date: today, lessonId: 'workplace-01', listeningDone: true, speakingDone: true,
  readingDone: true, writingDone: true, practiceMinutes: 25, confidence: 3,
  writingText: 'I am a backend engineer.', reflection: 'I spoke without stopping.',
  nextFocus: 'Speak more slowly.', completedAt: `${today}T10:30:00`, updatedAt: `${today}T10:30:00`,
})

describe('英语训练页', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.mocked(fetchEnglishLearning).mockResolvedValue(overview())
  })

  it('提供固定课程并在打开原文前完成真实听力路径', async () => {
    const wrapper = mount(EnglishLearningView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()

    expect(wrapper.text()).toContain('用三句话介绍自己')
    expect(wrapper.text()).not.toContain("Hi, I'm Wei. /")
    await wrapper.get('.listen-actions .ghost').trigger('click')
    expect(wrapper.text()).toContain("Hi, I'm Wei. /")
    wrapper.unmount()
  })

  it('编辑立即保留本机草稿，四项未完成时不能制造完成记录', async () => {
    const wrapper = mount(EnglishLearningView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()

    await wrapper.get('.writing-card textarea').setValue('My real introduction.')
    expect(localStorage.getItem(`wao-english-learning-session-draft-v1:${today}`)).toContain('My real introduction.')
    await wrapper.get('.save-row .complete').trigger('click')
    expect(wrapper.text()).toContain('先完成听说读写四项')
    expect(saveEnglishLearningSession).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('听说读写与信心齐全后保存完成并清除草稿', async () => {
    vi.mocked(saveEnglishLearningSession).mockResolvedValue(completedSession())
    vi.mocked(fetchEnglishLearning)
      .mockResolvedValueOnce(overview())
      .mockResolvedValueOnce(overview(completedSession()))
    const wrapper = mount(EnglishLearningView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()

    for (const input of wrapper.findAll('.practice-title input[type="checkbox"]')) await input.setValue(true)
    await wrapper.findAll('.reflection-grid fieldset button')[2]!.trigger('click')
    await wrapper.get('.writing-card textarea').setValue('I am a backend engineer.')
    await wrapper.get('.save-row .complete').trigger('click')
    await flushPromises()

    expect(saveEnglishLearningSession).toHaveBeenCalledWith(today, expect.objectContaining({
      listeningDone: true, speakingDone: true, readingDone: true, writingDone: true,
      confidence: 3, completed: true,
    }))
    expect(localStorage.getItem(`wao-english-learning-session-draft-v1:${today}`)).toBeNull()
    expect(wrapper.text()).toContain('本日已完成')
    wrapper.unmount()
  })

  it('课程表达可直接收进真实单词本', async () => {
    vi.mocked(captureVocabulary).mockResolvedValue({
      created: true,
      entry: { id: 've1', term: 'I mainly work with…', meaning: '', context: '', state: 'LEARNING',
        encounterCount: 1, reviewCount: 0, lastEncounteredAt: `${today}T10:00:00`,
        lastReviewedAt: null, createdAt: `${today}T10:00:00`, updatedAt: `${today}T10:00:00` },
    })
    const wrapper = mount(EnglishLearningView, { global: { stubs: { RouterLink: true } } })
    await flushPromises()

    await wrapper.get('.phrase-row button').trigger('click')
    await flushPromises()
    expect(captureVocabulary).toHaveBeenCalledWith('I mainly work with…')
    expect(wrapper.get('.phrase-row button').text()).toContain('已加入')
    wrapper.unmount()
  })
})
