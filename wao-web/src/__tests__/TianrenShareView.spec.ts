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
  createdAt: '2026-08-13T10:00:00',
}

describe('TianrenShareView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('免登录结果页展示同一卦象与隐私边界', async () => {
    vi.mocked(fetchSharedTianrenReading).mockResolvedValue(shared)
    const wrapper = mount(TianrenShareView)
    await flushPromises()

    expect(fetchSharedTianrenReading).toHaveBeenCalledWith('0123456789abcdef0123456789abcdef')
    expect(wrapper.text()).toContain('第 11 卦 · 泰')
    expect(wrapper.text()).toContain('泰')
    expect(wrapper.text()).toContain('升')
    expect(wrapper.findAll('.yao-row')).toHaveLength(6)
    expect(wrapper.text()).toContain('不含其私有 AI 对话')
  })

  it('无效分享链接显示明确错误状态', async () => {
    vi.mocked(fetchSharedTianrenReading).mockRejectedValue(new Error('分享链接无效'))
    const wrapper = mount(TianrenShareView)
    await flushPromises()

    expect(wrapper.text()).toContain('这个分享链接无效')
  })
})
