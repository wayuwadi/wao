import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import SelectionImageAction from '@/components/ui/SelectionImageAction.vue'
import { fetchArticleImage, startArticleImage } from '@/api/articleImages'

vi.mock('@/api/articleImages', () => ({ startArticleImage: vi.fn(), fetchArticleImage: vi.fn() }))

describe('小记选段生图', () => {
  beforeEach(() => { localStorage.clear(); vi.clearAllMocks() })
  afterEach(() => { document.body.innerHTML = '' })

  it('只用选中文字入队，先预览，确认后才加入小记', async () => {
    const id = `ac${'a'.repeat(24)}`
    vi.mocked(startArticleImage).mockResolvedValue({ id, status: 'QUEUED', message: '', imageUrl: null })
    vi.mocked(fetchArticleImage).mockResolvedValue({ id, status: 'SUCCEEDED', message: '', imageUrl: '/api/images/generated/raw' })
    const insertImage = vi.fn().mockReturnValue(true)
    const wrapper = mount(SelectionImageAction, { props: { scopeKey: 'note:member:one', insertImage }, attachTo: document.body })
    ;(wrapper.vm as unknown as { open: (source: string) => void }).open('选中的三点收获')
    await wrapper.vm.$nextTick()
    document.body.querySelector<HTMLButtonElement>('.selection-image-dialog button.primary')?.click()
    await flushPromises()
    expect(startArticleImage).toHaveBeenCalledWith('选中的三点收获', expect.stringContaining('选中的文字'), undefined, expect.stringMatching(/^[a-f0-9]{32}$/))
    expect(document.body.querySelector('.selection-image-dialog img')?.getAttribute('src')).toBe('/api/images/generated/raw')
    expect(insertImage).not.toHaveBeenCalled()
    document.body.querySelector<HTMLButtonElement>('.selection-image-dialog button.primary')?.click()
    await flushPromises()
    expect(insertImage).toHaveBeenCalledWith('/api/images/generated/raw')
    expect(localStorage.getItem('wao-note-selection-image-v1:note:member:one')).toBeNull()
    wrapper.unmount()
  })

  it('图片任务失败后保留选段并允许发起新任务', async () => {
    const first = `ac${'b'.repeat(24)}`, second = `ac${'c'.repeat(24)}`
    vi.mocked(startArticleImage)
      .mockResolvedValueOnce({ id: first, status: 'QUEUED', message: '', imageUrl: null })
      .mockResolvedValueOnce({ id: second, status: 'QUEUED', message: '', imageUrl: null })
    vi.mocked(fetchArticleImage)
      .mockResolvedValueOnce({ id: first, status: 'FAILED', message: '生成失败', imageUrl: null })
      .mockResolvedValueOnce({ id: second, status: 'SUCCEEDED', message: '', imageUrl: '/api/images/retry/raw' })
    const wrapper = mount(SelectionImageAction, { props: { scopeKey: 'note:member:two', insertImage: () => true }, attachTo: document.body })
    ;(wrapper.vm as unknown as { open: (source: string) => void }).open('失败后仍保留这段文字')
    await wrapper.vm.$nextTick()
    document.body.querySelector<HTMLButtonElement>('.selection-image-dialog button.primary')?.click()
    await flushPromises()
    expect(document.body.querySelector('.selection-image-dialog [role="alert"]')?.textContent).toContain('生成失败')
    expect(document.body.querySelector('.selection-image-dialog blockquote')?.textContent).toContain('仍保留这段文字')
    document.body.querySelector<HTMLButtonElement>('.selection-image-dialog button.primary')?.click()
    await flushPromises()
    expect(startArticleImage).toHaveBeenCalledTimes(2)
    expect(document.body.querySelector('.selection-image-dialog img')?.getAttribute('src')).toBe('/api/images/retry/raw')
    wrapper.unmount()
  })

  it('同一编辑器的二十个不同选段可同时提交，切换不丢原任务', async () => {
    vi.useFakeTimers()
    vi.mocked(startArticleImage).mockImplementation(async (_source, _prompt, _model, requestId) => ({ id: requestId!, status: 'QUEUED', message: '', imageUrl: null }))
    vi.mocked(fetchArticleImage).mockImplementation(async id => ({ id, status: 'RUNNING', message: '', imageUrl: null }))
    const wrapper = mount(SelectionImageAction, { props: { scopeKey: 'parallel', insertImage: () => true }, attachTo: document.body })
    try {
      for (let i = 0; i < 20; i++) {
        ;(wrapper.vm as unknown as { open: (source: string) => void }).open(`独立选段 ${i}`)
        await wrapper.vm.$nextTick()
        document.body.querySelector<HTMLButtonElement>('.selection-image-dialog button.primary')?.click()
        await flushPromises()
      }
      expect(startArticleImage).toHaveBeenCalledTimes(20)
      expect(document.body.querySelectorAll('.image-jobs button')).toHaveLength(20)
      expect(JSON.parse(localStorage.getItem('wao-note-selection-image-v1:parallel')!)).toHaveLength(20)
      document.body.querySelector<HTMLButtonElement>('.image-jobs button')?.click()
      await wrapper.vm.$nextTick()
      expect(document.body.querySelector('blockquote')?.textContent).toBe('独立选段 0')
    } finally { wrapper.unmount(); vi.clearAllTimers(); vi.useRealTimers() }
  })

  it('返回编辑器后用服务端任务 ID 恢复查询', async () => {
    const id = `ac${'d'.repeat(24)}`
    localStorage.setItem('wao-note-selection-image-v1:note:member:three', JSON.stringify({ id, source: '待恢复的选段' }))
    vi.mocked(fetchArticleImage).mockResolvedValue({ id, status: 'SUCCEEDED', message: '', imageUrl: '/api/images/resumed/raw' })
    const wrapper = mount(SelectionImageAction, { props: { scopeKey: 'note:member:three', insertImage: () => true }, attachTo: document.body })
    await flushPromises()
    expect(fetchArticleImage).toHaveBeenCalledWith(id)
    expect(document.body.querySelector('.selection-image-dialog img')?.getAttribute('src')).toBe('/api/images/resumed/raw')
    wrapper.unmount()
  })
})
