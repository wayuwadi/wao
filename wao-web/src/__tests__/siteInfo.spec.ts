import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import SiteFooter from '@/components/SiteFooter.vue'

vi.mock('@/api/system', () => ({
  fetchPublicSiteInfo: vi.fn<() => Promise<Record<string, string>>>().mockResolvedValue({
    siteName: 'WAO Life OS',
    description: '个人事务与 AI 协作系统',
    icpNumber: '豫ICP备2024105476号-1',
    icpUrl: 'https://beian.miit.gov.cn',
  }),
}))

describe('网站备案页脚', () => {
  it('展示网站名称、简介和可点击的备案号', async () => {
    const wrapper = mount(SiteFooter)
    await flushPromises()

    expect(wrapper.text()).toContain('WAO Life OS')
    expect(wrapper.text()).toContain('个人事务与 AI 协作系统')
    expect(wrapper.text()).toContain('豫ICP备2024105476号-1')
    expect(wrapper.get('a').attributes('href')).toBe('https://beian.miit.gov.cn')
    expect(wrapper.get('footer').classes()).not.toContain('reserve-mobile-actions')
  })

  it('仅在登录后的应用布局显式要求时为手机底部操作预留空间', async () => {
    const wrapper = mount(SiteFooter, { props: { reserveMobileActions: true } })
    await flushPromises()

    expect(wrapper.get('footer').classes()).toContain('reserve-mobile-actions')
  })

  it('配置接口失败时不阻塞页面，也不显示错误备案信息', async () => {
    const { fetchPublicSiteInfo } = await import('@/api/system')
    vi.mocked(fetchPublicSiteInfo).mockRejectedValueOnce(new Error('network'))
    const wrapper = mount(SiteFooter)
    await flushPromises()

    expect(wrapper.find('footer').exists()).toBe(false)
  })
})
