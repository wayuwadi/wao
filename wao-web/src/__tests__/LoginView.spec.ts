import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import LoginView from '@/views/LoginView.vue'
import { useSessionStore } from '@/stores/session'
import { mountFeishuQr } from '@/utils/feishuQr'
import { startFeishuLogin } from '@/api/feishu'

vi.mock('@/utils/feishuQr', () => ({ mountFeishuQr: vi.fn().mockResolvedValue(() => {}) }))
vi.mock('@/utils/feishuInApp', () => ({ isInFeishu: () => false, feishuInAppLogin: vi.fn() }))
vi.mock('@/api/feishu', () => ({ startFeishuLogin: vi.fn().mockResolvedValue(undefined) }))
vi.mock('@/api/system', () => ({
  fetchPublicSiteInfo: vi.fn().mockResolvedValue({ siteName: 'WAO' }),
}))

let wrapper: VueWrapper
async function setup() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginView },
      { path: '/notes', component: { template: '<p>小记</p>' } },
    ],
  })
  await router.push('/login?redirect=/notes')
  const pinia = createPinia()
  wrapper = mount(LoginView, { attachTo: document.body, global: { plugins: [pinia, router] } })
  await flushPromises()
  return { router, session: useSessionStore(pinia) }
}
async function open() {
  await wrapper.get('.actions .primary').trigger('click')
  await flushPromises()
}
async function email() {
  await wrapper.get('.other-methods button').trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(mountFeishuQr).mockResolvedValue(() => {})
  HTMLDialogElement.prototype.showModal = function () {
    this.setAttribute('open', '')
  }
  HTMLDialogElement.prototype.close = function () {
    this.removeAttribute('open')
    this.dispatchEvent(new Event('close'))
  }
})
afterEach(() => {
  wrapper?.unmount()
  document.body.replaceChildren()
})
describe('舞台登录与认证生命周期', () => {
  it('初始静音且不加载二维码，打开后仅提供飞书和邮箱', async () => {
    await setup()
    expect(mountFeishuQr).not.toHaveBeenCalled()
    expect(wrapper.get('.sound-toggle').attributes('aria-pressed')).toBe('false')
    await open()
    expect(mountFeishuQr).toHaveBeenCalledOnce()
    expect(wrapper.get('.other-methods').text()).toContain('邮箱')
    expect(wrapper.text()).not.toContain('微信')
    expect(wrapper.get('dialog').attributes()).toHaveProperty('open')
  })
  it('关闭、重新打开与切换方式保留本页邮箱输入', async () => {
    await setup()
    await open()
    await email()
    await wrapper.get('input[type=email]').setValue('draft@example.com')
    await wrapper.get('input[type=password]').setValue('draft-password')
    await wrapper.get('.close').trigger('click')
    await open()
    await email()
    expect((wrapper.get('input[type=email]').element as HTMLInputElement).value).toBe(
      'draft@example.com',
    )
    expect((wrapper.get('input[type=password]').element as HTMLInputElement).value).toBe(
      'draft-password',
    )
  })
  it('失败保留表单，成功回到原目标；提交期间禁止重复提交和关闭', async () => {
    const { session, router } = await setup()
    await open()
    await email()
    await wrapper.get('input[type=email]').setValue('test@example.com')
    await wrapper.get('input[type=password]').setValue('test-password')
    const login = vi
      .spyOn(session, 'emailLogin')
      .mockRejectedValueOnce(new Error('凭据错误，请重试'))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('[role=alert]').text()).toContain('凭据错误')
    expect((wrapper.get('input[type=password]').element as HTMLInputElement).value).toBe(
      'test-password',
    )
    let resolve!: () => void
    login.mockImplementationOnce(
      () =>
        new Promise<void>((r) => {
          resolve = r
        }),
    )
    await wrapper.get('form').trigger('submit')
    await wrapper.get('form').trigger('submit')
    expect(login).toHaveBeenCalledTimes(2)
    expect(wrapper.get('.close').attributes()).toHaveProperty('disabled')
    resolve()
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/notes')
    expect((wrapper.get('input[type=password]').element as HTMLInputElement).value).toBe('')
  })
  it('迟到的二维码清理，不污染新页面', async () => {
    await setup()
    let resolve!: (cleanup: () => void) => void
    vi.mocked(mountFeishuQr).mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r
        }),
    )
    await open()
    const active = vi.mocked(mountFeishuQr).mock.calls[0]![1]!
    await email()
    expect(active()).toBe(false)
    const cleanup = vi.fn()
    resolve(cleanup)
    await flushPromises()
    expect(cleanup).toHaveBeenCalledOnce()
    expect(wrapper.find('#feishu-qr').exists()).toBe(false)
  })
  it('二维码和授权失败可见且可以重试', async () => {
    await setup()
    vi.mocked(mountFeishuQr).mockRejectedValueOnce(new Error('offline'))
    await open()
    expect(wrapper.get('.qr-error').text()).toContain('重新加载二维码')
    await wrapper.get('.qr-error button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.qr-error').exists()).toBe(false)
    vi.mocked(startFeishuLogin).mockRejectedValueOnce(new Error('offline'))
    await wrapper.get('.authorization-cta').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('暂时无法连接飞书')
  })
})
