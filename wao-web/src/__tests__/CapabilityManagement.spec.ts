import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import CapabilityAwareLayout from '@/layouts/CapabilityAwareLayout.vue'
import SkillManagePanel from '@/components/capabilities/resources/SkillManagePanel.vue'
import { useSessionStore } from '@/stores/session'

const api = vi.hoisted(() => ({
  fetchPublisher: vi.fn(),
  fetchMySkills: vi.fn(),
  savePublisher: vi.fn(),
  privateShell: vi.fn(),
}))
vi.mock('@/api/skills', () => ({
  ...api,
  deleteSkillDraft: vi.fn(),
  setSkillListed: vi.fn(),
  submitSkill: vi.fn(),
}))
vi.mock('@/layouts/AppLayout.vue', () => ({
  default: {
    setup: () => {
      api.privateShell()
      return {}
    },
    template: '<p>成员工作台</p>',
  },
}))
vi.mock('@/components/SiteFooter.vue', () => ({ default: { template: '<footer>WAO</footer>' } }))

async function render(
  component: typeof CapabilityAwareLayout | typeof SkillManagePanel,
  authed: boolean,
) {
  const pinia = createPinia()
  setActivePinia(pinia)
  if (authed)
    useSessionStore().accept({
      accountId: 'wage-account',
      member: { id: 'wage-member', name: 'wage', initial: 'W', color: '#fff', role: '成员' },
      identities: [],
      createdAt: '',
    })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/capabilities', component, meta: { module: 'capabilities' } },
      { path: '/login', component: { template: '<p>登录</p>' } },
      { path: '/capabilities/publish/skill', component: { template: '<p>发布</p>' } },
    ],
  })
  await router.push('/capabilities')
  const wrapper = mount(component, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return wrapper
}

describe('能力中心公开入口和发布身份草稿', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.fetchPublisher.mockResolvedValue({ handle: 'wage', displayName: '原昵称' })
    api.fetchMySkills.mockResolvedValue([])
  })
  it('访客不挂载会请求私有 API 的成员布局，登录后恢复成员布局', async () => {
    const guest = await render(CapabilityAwareLayout, false)
    expect(guest.text()).toContain('登录 WAO')
    expect(api.privateShell).not.toHaveBeenCalled()
    guest.unmount()
    const member = await render(CapabilityAwareLayout, true)
    expect(member.text()).toContain('成员工作台')
    expect(api.privateShell).toHaveBeenCalledOnce()
    member.unmount()
  })
  it('昵称草稿跨页面恢复；保存失败保留输入，成功后清理本机草稿', async () => {
    let wrapper = await render(SkillManagePanel, true)
    await wrapper.get('input').setValue('未保存昵称')
    wrapper.unmount()
    wrapper = await render(SkillManagePanel, true)
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('未保存昵称')
    api.savePublisher.mockRejectedValueOnce(new Error('网络失败'))
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('网络失败')
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('未保存昵称')
    api.savePublisher.mockResolvedValueOnce({ handle: 'wage', displayName: '未保存昵称' })
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '保存昵称')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('昵称已保存')
    expect(localStorage.getItem('wao:capabilities:publisher-name:wage-member')).toBeNull()
    wrapper.unmount()
  })
})
