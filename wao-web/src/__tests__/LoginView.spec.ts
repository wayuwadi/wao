import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia } from 'pinia'
import LoginView from '@/views/LoginView.vue'
import { useSessionStore } from '@/stores/session'
import { mountFeishuQr } from '@/utils/feishuQr'
import { startFeishuLogin } from '@/api/feishu'

vi.mock('@/utils/feishuQr', () => ({
  mountFeishuQr: vi.fn<typeof mountFeishuQr>().mockResolvedValue(() => {}),
}))
vi.mock('@/utils/feishuInApp', () => ({
  isInFeishu: () => false,
  feishuInAppLogin: vi.fn<() => Promise<null>>(),
}))
vi.mock('@/api/feishu', () => ({
  startFeishuLogin: vi.fn<typeof startFeishuLogin>().mockResolvedValue(undefined),
}))
vi.mock('@/api/system', () => ({
  fetchPublicSiteInfo: vi
    .fn<() => Promise<{ siteName: string }>>()
    .mockResolvedValue({ siteName: 'WAO' }),
}))

let wrapper: VueWrapper
async function setup(path = '/login') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', component: LoginView },
      { path: '/breathe', component: LoginView },
      { path: '/notes', component: { template: '<p>小记</p>' } },
    ],
  })
  await router.push(path)
  const pinia = createPinia()
  wrapper = mount(LoginView, { attachTo: document.body, global: { plugins: [pinia, router] } })
  await flushPromises()
  return { router, session: useSessionStore(pinia) }
}
async function open() {
  await wrapper.get('.header-login').trigger('click')
  await flushPromises()
}
async function email() {
  await wrapper.get('.other-methods button').trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  sessionStorage.clear()
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
    expect(wrapper.findAll('.header-login')).toHaveLength(1)
    expect(wrapper.text()).not.toMatch(/创建账号|创建 WAO 账号|注册/)
    expect(mountFeishuQr).not.toHaveBeenCalled()
    expect(wrapper.get('.sound-toggle').attributes('aria-pressed')).toBe('false')
    await open()
    expect(mountFeishuQr).toHaveBeenCalledOnce()
    expect(wrapper.get('.other-methods').text()).toContain('邮箱')
    expect(wrapper.text()).not.toContain('微信')
    expect(wrapper.get('dialog').attributes()).toHaveProperty('open')
  })
  it('纯呼吸首页不创建伙伴场景或游戏实例，静坐从次级入口可用', async () => {
    const AudioMock = vi.fn<() => void>()
    vi.stubGlobal('Audio', AudioMock)
    await setup()
    expect(wrapper.find('.world-crew').exists()).toBe(false)
    expect(wrapper.find('iframe').exists()).toBe(false)
    expect(wrapper.get('.breathing-stage').classes()).toContain('minimal')
    expect(wrapper.findAll('.rainbow-tabs button')).toHaveLength(6)
    await wrapper
      .findAll('.page-more button')
      .find((button) => button.text() === '静坐')!
      .trigger('click')
    expect(wrapper.get('.actions .primary').text()).toContain('开始静坐')
    await wrapper.get('.home-link').trigger('click')
    expect(wrapper.get('.actions .primary').text()).toContain('开始呼吸')
    expect(AudioMock).not.toHaveBeenCalled()
    await open()
    expect(wrapper.find('.greeting-bubble').exists()).toBe(false)
    vi.unstubAllGlobals()
  })
  it('可视视口缩小时更新弹窗尺寸并在卸载时解绑', async () => {
    const viewport = Object.assign(new EventTarget(), { height: 400, offsetTop: 0 })
    const remove = vi.spyOn(viewport, 'removeEventListener')
    vi.stubGlobal('visualViewport', viewport)
    await setup()
    viewport.height = 260
    viewport.offsetTop = 80
    viewport.dispatchEvent(new Event('resize'))
    await flushPromises()
    const style = (wrapper.get('dialog').element as HTMLElement).style
    expect(style.getPropertyValue('--login-viewport-height')).toBe('260px')
    expect(style.getPropertyValue('--login-viewport-top')).toBe('80px')
    wrapper.unmount()
    expect(remove).toHaveBeenCalledWith('resize', expect.any(Function))
    expect(remove).toHaveBeenCalledWith('scroll', expect.any(Function))
    vi.unstubAllGlobals()
  })
  it('选中静坐、游戏或布局后关闭菜单，避免残留菜单挡住游戏控制', async () => {
    await setup('/breathe')
    const menu = wrapper.get<HTMLDetailsElement>('.page-more')
    menu.element.open = true
    await wrapper.findAll('.page-more button').find(b => b.text() === '静坐')!.trigger('click')
    expect(menu.element.open).toBe(false)
    menu.element.open = true
    await wrapper.findAll('.rainbow-tabs button').find(b => b.text() === '把伙伴送回家')!.trigger('click')
    expect(menu.element.open).toBe(false)
    const styles = wrapper.get<HTMLDetailsElement>('.style-picker')
    styles.element.open = true
    await styles.findAll('button').find(b => b.text() === '涂鸦手账')!.trigger('click')
    expect(styles.element.open).toBe(false)
    expect(wrapper.get('main').classes()).toContain('world-journal')
  })
  it('关闭、重新打开与切换方式保留本页邮箱输入', async () => {
    await setup()
    await open()
    await email()
    expect(wrapper.text()).not.toMatch(/创建账号|注册/)
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
    const { session, router } = await setup('/login?redirect=/notes')
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
    const cleanup = vi.fn<() => void>()
    resolve(cleanup)
    await flushPromises()
    expect(cleanup).toHaveBeenCalledOnce()
    expect(wrapper.find('#feishu-qr').exists()).toBe(false)
  })
  it('离开页面后邮箱登录完成也不抢回导航', async () => {
    const { session, router } = await setup('/login?redirect=/notes')
    await email()
    let resolve!: () => void
    vi.spyOn(session, 'emailLogin').mockImplementationOnce(
      () =>
        new Promise<void>((done) => {
          resolve = done
        }),
    )
    await wrapper.get('form').trigger('submit')
    wrapper.unmount()
    await router.push('/breathe')
    resolve()
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/breathe')
  })
  it('离开页面后取消迟到的飞书授权跳转，也不保存快照', async () => {
    const { router } = await setup()
    await open()
    await wrapper.get('.authorization-cta').trigger('click')
    const beforeNavigate = vi.mocked(startFeishuLogin).mock.calls[0]![1]!
    wrapper.unmount()
    await router.push('/notes')
    expect(beforeNavigate('https://auth.example/?state=late')).toBe(false)
    expect(sessionStorage.length).toBe(0)
    expect(router.currentRoute.value.path).toBe('/notes')
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
  it('公共空间中真实状态接收流程保持练习暂停，不导航且不持久化密码', async () => {
    const { session, router } = await setup()
    await wrapper.get('.duration button').trigger('click')
    await wrapper.get('.actions .primary').trigger('click')
    await open()
    expect(wrapper.get('.breathing-stage').classes()).toContain('paused')
    const remaining = wrapper.get('.timer').text()
    await email()
    await wrapper.get('input[type=email]').setValue('test@example.com')
    await wrapper.get('input[type=password]').setValue('private-password')
    vi.spyOn(session, 'emailLogin').mockImplementation(async () => {
      session.accept({
        accountId: 'account',
        member: { id: 'member', name: '成员', initial: '成', color: '#aaa', role: '成员' },
        identities: [],
        createdAt: '',
      })
    })
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/login')
    expect(wrapper.get('.breathing-stage').classes()).toContain('paused')
    expect(wrapper.get('.timer').text()).toBe(remaining)
    expect(wrapper.get('.header-login').text()).toContain('进入工作空间')
    expect(JSON.stringify(localStorage)).not.toContain('private-password')
    expect(JSON.stringify(sessionStorage)).not.toContain('private-password')
  })
})
