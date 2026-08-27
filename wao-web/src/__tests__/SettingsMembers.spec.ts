import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'
import { useSessionStore } from '@/stores/session'
import type { AuthSession, SessionMember } from '@/api/auth'

const authApi = vi.hoisted(() => ({
  apiCreateAccount: vi.fn<(name: string, email: string, password: string) => Promise<SessionMember>>(),
  updateEmailCredential: vi.fn<(email: string, currentPassword: string, newPassword: string) => Promise<AuthSession>>(),
  updateProfile: vi.fn<(name: string, initial: string) => Promise<AuthSession>>(),
  unlinkIdentity: vi.fn<(provider: string) => Promise<AuthSession>>(),
}))

vi.mock('@/api/auth', () => ({
  ...authApi,
  claimMember: vi.fn<(memberId: string) => Promise<AuthSession>>(),
  getSession: vi.fn<() => Promise<AuthSession>>(),
  loginWithEmail: vi.fn<(email: string, password: string) => Promise<AuthSession>>(),
  logoutSession: vi.fn<() => Promise<void>>(),
  registerWithEmail: vi.fn<(name: string, email: string, password: string) => Promise<AuthSession>>(),
}))

describe('设置页真实成员账号创建', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    authApi.apiCreateAccount.mockResolvedValue({
      id: 'member-new', name: '新成员', initial: '新', color: '#123456', role: '成员',
    })
  })

  it('管理员提交姓名邮箱密码后创建账号并刷新成员目录', async () => {
    const session = useSessionStore()
    session.accept({
      accountId: 'account-admin', identities: [], createdAt: '2026-08-27T09:00:00+08:00',
      member: { id: 'admin', name: '管理员', initial: '管', color: '#4358D6', role: '管理员' },
    })
    session.members = [session.currentUser]
    session.org = { id: 'wao', name: 'WAO', memberCount: 1 }
    const refresh = vi.spyOn(session, 'refreshDirectory').mockImplementation(async () => {
      session.members = [...session.members, {
        id: 'member-new', name: '新成员', initial: '新', color: '#123456', role: '成员',
      }]
      session.org.memberCount = 2
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/settings', component: { template: '<div />' } }],
    })
    await router.push('/settings?tab=members')
    await router.isReady()
    const wrapper = mount(SettingsView, { global: { plugins: [router] } })

    await wrapper.get('.card-row .btn-primary').trigger('click')
    const inputs = wrapper.findAll('.member-account-form input')
    await inputs[0]!.setValue('新成员')
    await inputs[1]!.setValue('new@example.com')
    await inputs[2]!.setValue('password-123')
    await wrapper.get('.member-account-form').trigger('submit')
    await flushPromises()

    expect(authApi.apiCreateAccount).toHaveBeenCalledWith('新成员', 'new@example.com', 'password-123')
    expect(refresh).toHaveBeenCalledOnce()
    expect(wrapper.find('.member-account-form').exists()).toBe(false)
    expect(wrapper.text()).toContain('新成员')
  })

  it('服务端拒绝时保留输入并显示真实错误', async () => {
    authApi.apiCreateAccount.mockRejectedValueOnce(new Error('该邮箱已经绑定到其他账号'))
    const session = useSessionStore()
    session.accept({
      accountId: 'account-admin', identities: [], createdAt: '2026-08-27T09:00:00+08:00',
      member: { id: 'admin', name: '管理员', initial: '管', color: '#4358D6', role: '管理员' },
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/settings', component: { template: '<div />' } }],
    })
    await router.push('/settings?tab=members')
    await router.isReady()
    const wrapper = mount(SettingsView, { global: { plugins: [router] } })

    await wrapper.get('.card-row .btn-primary').trigger('click')
    const inputs = wrapper.findAll('.member-account-form input')
    await inputs[0]!.setValue('重复成员')
    await inputs[1]!.setValue('used@example.com')
    await inputs[2]!.setValue('password-123')
    await wrapper.get('.member-account-form').trigger('submit')
    await flushPromises()

    expect(wrapper.get('.member-account-form .form-error').text()).toContain('该邮箱已经绑定到其他账号')
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('重复成员')
  })
})
