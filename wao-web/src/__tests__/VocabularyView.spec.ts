import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import VocabularyView from '@/views/VocabularyView.vue'
import {
  captureVocabulary,
  fetchVocabulary,
  reviewVocabulary,
  updateVocabulary,
  type VocabularyEntry,
} from '@/api/vocabulary'

vi.mock('@/api/vocabulary', () => ({
  captureVocabulary: vi.fn(),
  deleteVocabulary: vi.fn(),
  fetchVocabulary: vi.fn(),
  reviewVocabulary: vi.fn(),
  updateVocabulary: vi.fn(),
}))

function entry(patch: Partial<VocabularyEntry> = {}): VocabularyEntry {
  return {
    id: 've1',
    term: 'resilient',
    meaning: '韧性的',
    context: 'a resilient system',
    state: 'LEARNING',
    encounterCount: 1,
    reviewCount: 0,
    lastEncounteredAt: '2026-08-12T10:00:00',
    lastReviewedAt: null,
    createdAt: '2026-08-12T10:00:00',
    updatedAt: '2026-08-12T10:00:00',
    ...patch,
  }
}

describe('个人英语单词本', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.clearAllMocks()
    vi.mocked(fetchVocabulary).mockResolvedValue([entry()])
  })

  it('快速粘贴重复词时提示累计遇见次数并清空草稿', async () => {
    vi.mocked(captureVocabulary).mockResolvedValue({
      entry: entry({ encounterCount: 2 }),
      created: false,
    })
    const wrapper = mount(VocabularyView)
    await flushPromises()

    await wrapper.get('#vocabulary-term').setValue('resilient')
    expect(localStorage.getItem('wao-vocabulary-capture-draft')).toBe('resilient')
    await wrapper.get('.capture-form').trigger('submit')
    await flushPromises()

    expect(captureVocabulary).toHaveBeenCalledWith('resilient')
    expect(wrapper.text()).toContain('又遇见一次：resilient（共 2 次）')
    expect(wrapper.get('#vocabulary-term').element).toHaveProperty('value', '')
    expect(localStorage.getItem('wao-vocabulary-capture-draft')).toBeNull()
    wrapper.unmount()
  })

  it('编辑笔记立即保存本机草稿，接口失败后可重试', async () => {
    vi.mocked(updateVocabulary)
      .mockRejectedValueOnce(new Error('网络不可用'))
      .mockResolvedValueOnce(entry({ meaning: '能快速恢复的' }))
    const wrapper = mount(VocabularyView)
    await flushPromises()

    await wrapper.get('.entry-row').trigger('click')
    await wrapper.findAll('textarea')[0]!.setValue('能快速恢复的')
    expect(localStorage.getItem('wao-vocabulary-edit-draft:ve1')).toContain('能快速恢复的')
    await wrapper.get('.save-button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('网络不可用')
    expect(wrapper.get('.save-button').text()).toContain('重试保存')

    await wrapper.get('.save-button').trigger('click')
    await flushPromises()
    expect(updateVocabulary).toHaveBeenCalledTimes(2)
    expect(localStorage.getItem('wao-vocabulary-edit-draft:ve1')).toBeNull()
    expect(wrapper.text()).toContain('已保存')
    wrapper.unmount()
  })

  it('主动回忆显示笔记后才记录复习结果', async () => {
    vi.mocked(reviewVocabulary).mockResolvedValue(entry({ state: 'MASTERED', reviewCount: 1 }))
    const wrapper = mount(VocabularyView)
    await flushPromises()

    await wrapper.get('.summary-row > button').trigger('click')
    expect(wrapper.text()).toContain('先在心里想一想')
    expect(reviewVocabulary).not.toHaveBeenCalled()
    await wrapper.get('.reveal-button').trigger('click')
    expect(wrapper.text()).toContain('a resilient system')
    await wrapper.findAll('.review-actions button')[1]!.trigger('click')
    await flushPromises()

    expect(reviewVocabulary).toHaveBeenCalledWith('ve1', 'MASTERED')
    expect(wrapper.find('.review-card').exists()).toBe(false)
    wrapper.unmount()
  })

  it('复习结果更新掌握状态但不覆盖尚未保存的笔记草稿', async () => {
    vi.mocked(reviewVocabulary).mockResolvedValue(entry({ state: 'MASTERED', reviewCount: 1 }))
    const wrapper = mount(VocabularyView)
    await flushPromises()

    await wrapper.get('.entry-row').trigger('click')
    await wrapper.findAll('textarea')[0]!.setValue('我的未保存理解')
    await wrapper.get('.review-button').trigger('click')
    await wrapper.get('.reveal-button').trigger('click')
    await wrapper.findAll('.review-actions button')[1]!.trigger('click')
    await flushPromises()

    const draft = JSON.parse(localStorage.getItem('wao-vocabulary-edit-draft:ve1') ?? '{}')
    expect(draft).toMatchObject({ meaning: '我的未保存理解', state: 'MASTERED' })
    expect(wrapper.findAll('textarea')[0]!.element).toHaveProperty('value', '我的未保存理解')
    expect(wrapper.get('input[value="MASTERED"]').element).toHaveProperty('checked', true)
    wrapper.unmount()
  })
})
