import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TianrenShareView from '@/views/TianrenShareView.vue'
import { fetchSharedTianrenReading, type SharedTianrenReading } from '@/api/tianren'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { token: '0123456789abcdef0123456789abcdef' } }),
}))

vi.mock('@/api/tianren', () => ({
  fetchSharedTianrenReading: vi.fn<(typeof import('@/api/tianren'))['fetchSharedTianrenReading']>(),
}))

vi.mock('@/components/SiteFooter.vue', () => ({ default: { template: '<footer />' } }))

const shared: SharedTianrenReading = {
  date: '2026-08-13',
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
  interpretation: '## 四时自照\n\n把今天的任务收敛到一件小事。<script>alert(1)</script>',
  createdAt: '2026-08-13T10:00:00',
}

describe('TianrenShareView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('免登录结果页展示同一卦象、最新 AI 自照与隐私边界', async () => {
    vi.mocked(fetchSharedTianrenReading).mockResolvedValue(shared)
    const wrapper = mount(TianrenShareView)
    await flushPromises()

    expect(fetchSharedTianrenReading).toHaveBeenCalledWith('0123456789abcdef0123456789abcdef')
    expect(wrapper.text()).toContain('第 11 卦 · 泰')
    expect(wrapper.text()).toContain('泰')
    expect(wrapper.text()).toContain('升')
    expect(wrapper.findAll('.yao-row')).toHaveLength(6)
    expect(wrapper.text()).toContain('天人一卦 · AI 自照')
    expect(wrapper.text()).toContain('把今天的任务收敛到一件小事')
    expect(wrapper.find('.markdown h2').text()).toBe('四时自照')
    expect(wrapper.find('.markdown').html()).not.toContain('<script>')
    expect(wrapper.text()).toContain('不单独公开关注点原文或完整会话')
  })

  it('尚无 AI 回复时不显示空的自照卡片', async () => {
    vi.mocked(fetchSharedTianrenReading).mockResolvedValue({ ...shared, interpretation: null })
    const wrapper = mount(TianrenShareView)
    await flushPromises()

    expect(wrapper.find('.interpretation-card').exists()).toBe(false)
    expect(wrapper.text()).toContain('文化反思，不是医疗建议')
  })

  it('无效分享链接显示明确错误状态', async () => {
    vi.mocked(fetchSharedTianrenReading).mockRejectedValue(new Error('分享链接无效'))
    const wrapper = mount(TianrenShareView)
    await flushPromises()

    expect(wrapper.text()).toContain('这个分享链接无效')
  })
})
