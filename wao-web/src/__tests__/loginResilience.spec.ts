import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import AuthDialog from '@/components/login/AuthDialog.vue'
import { useSessionStore } from '@/stores/session'
import * as auth from '@/api/auth'
import { mountFeishuQr } from '@/utils/feishuQr'
import { preferredLoginMethod, rememberLoginMethod } from '@/utils/loginPreference'
import { isSessionPaused, resumeSessionRequests } from '@/utils/sessionRecovery'
import { reauthenticateWithFeishu } from '@/utils/reauthPopup'

vi.mock('@/api/auth', () => ({
  getSession: vi.fn(),
  loginWithEmail: vi.fn(),
  logoutSession: vi.fn(),
  registerWithEmail: vi.fn(),
  claimMember: vi.fn(),
}))
vi.mock('@/api/board', () => ({ fetchMembers: vi.fn(), fetchOrg: vi.fn() }))
vi.mock('@/api/desktopCache', () => ({ setDesktopCacheMember: vi.fn() }))
vi.mock('@/utils/feishuQr', () => ({ mountFeishuQr: vi.fn().mockResolvedValue(() => {}) }))
vi.mock('@/utils/reauthPopup', () => ({
  reauthenticateWithFeishu: vi.fn().mockResolvedValue(undefined),
}))
vi.mock('@/utils/feishuInApp', () => ({ isInFeishu: () => false, feishuInAppLogin: vi.fn() }))
const account = { accountId: 'a1', member: { id: 'u1', name: '原用户' } } as auth.AuthSession
let wrapper: ReturnType<typeof mount> | undefined
beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  resumeSessionRequests()
  vi.clearAllMocks()
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
  wrapper = undefined
  document.body.replaceChildren()
  vi.restoreAllMocks()
  resumeSessionRequests()
})
async function setup(recovery = false) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/notes', component: { template: '<textarea>未保存的草稿</textarea>' } }],
  })
  await router.push('/notes?draft=1')
  wrapper = mount(AuthDialog, {
    attachTo: document.body,
    props: { target: '/notes', beforeNavigate: () => {}, recovery },
    global: { plugins: [router] },
  })
  await (wrapper.vm as unknown as { open: () => Promise<void> }).open()
  return router
}
it('移动浏览器先提供授权，扫码入口可切换', async () => {
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('iPhone Safari')
  await setup()
  expect(mountFeishuQr).not.toHaveBeenCalled()
  expect(wrapper!.get('.authorization-cta').text()).toContain('使用飞书授权登录')
  await wrapper!
    .findAll('button')
    .find((b) => b.text() === '改用二维码扫码')!
    .trigger('click')
  await flushPromises()
  expect(mountFeishuQr).toHaveBeenCalledOnce()
})
it('仅记住成功方式，密码显示开关不持久化凭据', async () => {
  rememberLoginMethod('email')
  await setup()
  expect(mountFeishuQr).not.toHaveBeenCalled()
  await wrapper!.get('input[type=email]').setValue('demo@example.invalid')
  await wrapper!.get('input[type=password]').setValue('sample-password')
  await wrapper!
    .findAll('button')
    .find((b) => b.text() === '显示密码')!
    .trigger('click')
  expect(wrapper!.get('input[autocomplete=current-password]').attributes('type')).toBe('text')
  vi.mocked(auth.loginWithEmail).mockRejectedValueOnce(new Error('密码错误'))
  await wrapper!.get('form').trigger('submit')
  await flushPromises()
  expect(preferredLoginMethod()).toBe('email')
  expect(JSON.stringify(localStorage)).not.toContain('sample-password')
  expect(JSON.stringify(localStorage)).not.toContain('demo@example.invalid')
})
it('二维码过期清理旧二维码并给出刷新入口', async () => {
  const cleanup = vi.fn()
  vi.mocked(mountFeishuQr).mockResolvedValueOnce(cleanup)
  await setup()
  const calls = vi.mocked(mountFeishuQr).mock.calls
  const expired = calls[calls.length - 1]![3]!
  expired()
  await flushPromises()
  expect(cleanup).toHaveBeenCalledOnce()
  expect(wrapper!.text()).toContain('二维码已过期')
  await wrapper!.get('.qr-error button').trigger('click')
  await flushPromises()
  expect(mountFeishuQr).toHaveBeenCalledTimes(2)
})
it('恢复只接受同账号；错误身份保留页面身份和请求暂停', async () => {
  const session = useSessionStore()
  session.accept(account)
  session.requireRecovery()
  vi.mocked(auth.getSession).mockResolvedValueOnce({ ...account, accountId: 'other' })
  await expect(session.recover()).rejects.toThrow('原账号')
  expect(session.account).toEqual(account)
  expect(session.userId).toBe('u1')
  expect(session.recoveryRequired).toBe(true)
  expect(isSessionPaused()).toBe(true)
  vi.mocked(auth.getSession).mockResolvedValueOnce(account)
  await session.recover()
  expect(session.recoveryRequired).toBe(false)
  expect(isSessionPaused()).toBe(false)
})
it('邮箱原地恢复保持地址和页面，失败不切换身份', async () => {
  const session = useSessionStore()
  session.accept(account)
  session.requireRecovery()
  rememberLoginMethod('email')
  const router = await setup(true)
  vi.mocked(auth.loginWithEmail)
    .mockResolvedValueOnce({ ...account, accountId: 'other' })
    .mockResolvedValueOnce(account)
  await wrapper!.get('input[type=email]').setValue('demo@example.invalid')
  await wrapper!.get('input[type=password]').setValue('sample-password')
  await wrapper!.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper!.text()).toContain('原账号')
  expect(session.recoveryRequired).toBe(true)
  await wrapper!.get('form').trigger('submit')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/notes?draft=1')
  expect(session.recoveryRequired).toBe(false)
})
it('飞书恢复使用独立窗口并读取真实会话，不跳转原地址', async () => {
  const session = useSessionStore()
  session.accept(account)
  session.requireRecovery()
  const router = await setup(true)
  vi.mocked(auth.getSession).mockResolvedValueOnce(account)
  await wrapper!.get('.authorization-cta').trigger('click')
  await flushPromises()
  expect(reauthenticateWithFeishu).toHaveBeenCalledOnce()
  expect(auth.getSession).toHaveBeenCalledOnce()
  expect(router.currentRoute.value.fullPath).toBe('/notes?draft=1')
  expect(preferredLoginMethod()).toBe('feishu')
})
