import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import LoginView from '@/views/LoginView.vue'

vi.mock('@/utils/feishuQr', () => ({
  mountFeishuQr: vi.fn<() => Promise<() => void>>().mockResolvedValue(() => {}),
}))
vi.mock('@/api/system', () => ({
  fetchPublicSiteInfo: vi.fn<() => Promise<Record<string, string>>>().mockResolvedValue({
    siteName: 'WAO', description: '', icpNumber: '', icpUrl: '',
  }),
}))

describe('登录页登录方式', () => {
  it('只提供飞书与邮箱登录入口', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginView }],
    })
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginView, { global: { plugins: [createPinia(), router] } })
    await flushPromises()
    expect(wrapper.find('.other-methods').text()).toContain('邮箱')
    expect(wrapper.text()).toContain('飞书')
    expect(wrapper.text()).not.toContain('微信')
    wrapper.unmount()
  })
})
