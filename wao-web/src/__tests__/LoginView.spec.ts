import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import LoginView from '@/views/LoginView.vue'

vi.mock('@/utils/feishuQr', () => ({
  mountFeishuQr: vi.fn<() => Promise<() => void>>().mockResolvedValue(() => {}),
}))
vi.mock('@/api/system', () => ({
  fetchPublicSiteInfo: vi.fn<() => Promise<unknown>>().mockResolvedValue({
    siteName: 'WAO', description: '', icpNumber: '豫ICP备2024105476号-1',
    icpUrl: 'https://beian.miit.gov.cn',
  }),
}))

describe('登录页登录方式', () => {
  it('只提供飞书和邮箱入口，不展示无权限的微信登录', async () => {
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
    expect(methods).not.toContain('微信')
    wrapper.unmount()
  })
})
