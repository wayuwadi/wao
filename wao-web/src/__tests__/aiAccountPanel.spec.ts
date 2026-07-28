import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AiAccountPanel from '@/components/settings/AiAccountPanel.vue'
import type { AiAccount } from '@/api/aiAccount'

const fetchAiAccounts = vi.fn<() => Promise<AiAccount[]>>()
const startAiAccountLogin = vi.fn<() => Promise<unknown>>()
const completeAiAccountLogin = vi.fn<() => Promise<unknown>>()

vi.mock('@/api/aiAccount', () => ({
  fetchAiAccounts: () => fetchAiAccounts(),
  createAiAccount: vi.fn(),
  updateAiAccount: vi.fn(),
  deleteAiAccount: vi.fn(),
  syncAiAccount: vi.fn(),
  startAiAccountLogin: () => startAiAccountLogin(),
  completeAiAccountLogin: (state: string, code: string) => completeAiAccountLogin(state, code),
}))

function account(patch: Partial<AiAccount> = {}): AiAccount {
  return {
    id: 'aa-1',
    provider: 'claude',
    label: '娃哥-Claude',
    email: 'a@b.com',
    enabled: true,
    authMethod: 'oauth',
    plan: 'pro',
    quotaStatus: 'SYNCED',
    quotaMessage: '七天额度已用 92%',
    quotaWindow: 'weekly_all',
    quotaPercent: 92,
    quotaResetsAt: '2026-07-28T21:00:00',
    quotaRawJson: null,
    tokenExpiresAt: null,
    lastSyncedAt: '2026-07-28T12:00:00',
    createdAt: '2026-07-28T10:00:00',
    ...patch,
  }
}

describe('AiAccountPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('open', vi.fn())
  })

  it('把额度画成进度条并标出窗口与重置时间', async () => {
    fetchAiAccounts.mockResolvedValue([account()])

    const wrapper = mount(AiAccountPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('七天额度')
    expect(wrapper.text()).toContain('92%')
    const fill = wrapper.find('.quota-fill')
    expect(fill.exists()).toBe(true)
    // 92% 属于告警区间，条应该是红的而不是绿的。
    expect(fill.classes()).toContain('danger')
    expect(fill.attributes('style')).toContain('92%')
  })

  it('额度查不到时显示原因而不是 0%', async () => {
    fetchAiAccounts.mockResolvedValue([
      account({ quotaPercent: null, quotaStatus: 'NO_API', quotaMessage: 'Qoder 暂无公开的额度查询 API' }),
    ])

    const wrapper = mount(AiAccountPanel)
    await flushPromises()

    expect(wrapper.find('.quota-fill').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('0%')
    expect(wrapper.text()).toContain('Qoder 暂无公开的额度查询 API')
  })

  it('点登录后打开授权页并显示回填授权码的输入框', async () => {
    fetchAiAccounts.mockResolvedValue([])
    startAiAccountLogin.mockResolvedValue({
      state: 'st-1',
      authorizeUrl: 'https://claude.com/cai/oauth/authorize?x=1',
      expiresAt: '2026-07-28T13:40:00',
      hint: '完成授权后复制授权码',
    })

    const wrapper = mount(AiAccountPanel)
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().includes('使用 Claude 账号登录'))!.trigger('click')
    await flushPromises()

    expect(window.open).toHaveBeenCalledWith(
      'https://claude.com/cai/oauth/authorize?x=1', '_blank', 'noopener')
    expect(wrapper.find('.login-code').exists()).toBe(true)
    expect(wrapper.text()).toContain('完成授权后复制授权码')
  })

  it('授权码为空时不调用完成登录接口', async () => {
    fetchAiAccounts.mockResolvedValue([])
    startAiAccountLogin.mockResolvedValue({
      state: 'st-1', authorizeUrl: 'https://example.invalid/a', expiresAt: '', hint: '',
    })

    const wrapper = mount(AiAccountPanel)
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().includes('使用 Claude 账号登录'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().includes('完成登录'))!.trigger('click')
    await flushPromises()

    expect(completeAiAccountLogin).not.toHaveBeenCalled()
  })
})
