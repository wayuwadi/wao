import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import LoginView from '@/views/LoginView.vue'
import { createWechatOfficialSession } from '@/api/wechat'

vi.mock('@/utils/feishuQr', () => ({
  mountFeishuQr: vi.fn<() => Promise<() => void>>().mockResolvedValue(() => {}),
}))
vi.mock('@/api/wechat', () => ({
  createWechatOfficialSession: vi.fn<() => Promise<{
    sessionId: string
    qrCodeUrl: string
    verificationCode: string
    expiresAt: string
  }>>().mockResolvedValue({
    sessionId: 'wechat-session',
    qrCodeUrl: 'https://mp.weixin.qq.com/qr/test',
    verificationCode: '2503',
    expiresAt: '2026-08-31T10:05:00Z',
  }),
  pollWechatOfficialSession: vi
    .fn<() => Promise<{ status: 'WAITING_SCAN'; resolution: null }>>()
    .mockResolvedValue({ status: 'WAITING_SCAN', resolution: null }),
}))
vi.mock('@/api/system', () => ({
  fetchPublicSiteInfo: vi.fn<() => Promise<Record<string, string>>>().mockResolvedValue({
    siteName: 'WAO',
    description: '',
    icpNumber: '豫ICP备2024105476号-1',
    icpUrl: 'https://beian.miit.gov.cn',
  }),
}))

describe('登录页登录方式', () => {
  beforeEach(() => {
    vi.mocked(createWechatOfficialSession).mockResolvedValue({
      sessionId: 'wechat-session',
      qrCodeUrl: 'https://mp.weixin.qq.com/qr/test',
      verificationCode: '2503',
      expiresAt: '2026-08-31T10:05:00Z',
    })
  })

  it('提供公众号扫码验证码登录入口并展示真实会话信息', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginView }],
    })
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [createPinia(), router] } })
    await flushPromises()

    const methods = wrapper.find('.other-methods').text()
    expect(methods).toContain('邮箱')
    expect(methods).toContain('微信')

    const wechatButton = wrapper
      .findAll('.other-methods button')
      .find((button) => button.text().includes('微信'))
    await wechatButton!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.wechat-qr').attributes('src')).toBe('https://mp.weixin.qq.com/qr/test')
    expect(wrapper.find('.wechat-code').text()).toBe('2503')
    expect(wrapper.text()).toContain('向公众号发送下方 4 位验证码')
    wrapper.unmount()
  })

  it('公众号出口未进白名单时直接显示微信返回的具体 IP', async () => {
    vi.mocked(createWechatOfficialSession).mockRejectedValue(
      new Error('微信拒绝服务器出口 IP 171.83.123.164，请将它加入公众号后台的 IP 白名单（微信错误码 40164）'),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginView }],
    })
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [createPinia(), router] } })
    await flushPromises()

    const wechatButton = wrapper
      .findAll('.other-methods button')
      .find((button) => button.text().includes('微信'))
    await wechatButton!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('171.83.123.164')
    expect(wrapper.text()).toContain('IP 白名单')
    expect(wrapper.text()).toContain('错误码 40164')
    expect(wrapper.text()).toContain('重新获取')
    wrapper.unmount()
  })
})
