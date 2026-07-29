import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AiAccountPanel from '@/components/settings/AiAccountPanel.vue'
import type { AiAccount } from '@/api/aiAccount'

const fetchAiAccounts = vi.fn<() => Promise<AiAccount[]>>()
const createAiAccount = vi.fn<(payload: unknown) => Promise<unknown>>()
const updateAiAccount = vi.fn<(id: string, payload: unknown) => Promise<unknown>>()
const deleteAiAccount = vi.fn<(id: string) => Promise<unknown>>()
const syncAiAccount = vi.fn<(id: string) => Promise<unknown>>()
const startAiAccountLogin = vi.fn<(provider: 'claude' | 'codex') => Promise<unknown>>()
const completeAiAccountLogin = vi.fn<(state: string, code: string) => Promise<unknown>>()
const fetchAiAccountLoginStatus = vi.fn<(state: string) => Promise<unknown>>()

vi.mock('@/api/aiAccount', () => ({
  fetchAiAccounts: () => fetchAiAccounts(),
  createAiAccount: (payload: unknown) => createAiAccount(payload),
  updateAiAccount: (id: string, payload: unknown) => updateAiAccount(id, payload),
  deleteAiAccount: (id: string) => deleteAiAccount(id),
  syncAiAccount: (id: string) => syncAiAccount(id),
  startAiAccountLogin: (provider: 'claude' | 'codex') => startAiAccountLogin(provider),
  completeAiAccountLogin: (state: string, code: string) => completeAiAccountLogin(state, code),
  fetchAiAccountLoginStatus: (state: string) => fetchAiAccountLoginStatus(state),
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

  it('点击查看额度后展开套餐、凭据有效期和全部额度窗口', async () => {
    fetchAiAccounts.mockResolvedValue([account({
      tokenExpiresAt: '2026-07-30T11:58:47',
      quotaRawJson: JSON.stringify({
        limits: [
          { kind: 'five_hour', percent: 35, resets_at: '2026-07-29T04:27:30+08:00' },
          { kind: 'weekly_all', percent: 63, resets_at: '2026-08-04T13:46:00+08:00' },
        ],
      }),
    })])
    const wrapper = mount(AiAccountPanel)
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('查看额度'))!.trigger('click')

    const details = wrapper.find('.quota-details')
    expect(details.exists()).toBe(true)
    expect(details.text()).toContain('OAuth 登录')
    expect(details.text()).toContain('套餐')
    expect(details.text()).toContain('Pro')
    expect(details.text()).toContain('凭据有效期')
    expect(details.text()).toContain('五小时会话额度')
    expect(details.text()).toContain('已用 35%')
    expect(details.text()).toContain('七天额度')
    expect(details.text()).toContain('已用 63%')
    expect(wrapper.text()).toContain('收起详情')
  })

  it('刷新额度调用真实同步接口并重新读取账号', async () => {
    fetchAiAccounts.mockResolvedValue([account()])
    syncAiAccount.mockResolvedValue({
      id: 'aa-1', provider: 'claude', status: 'SYNCED', message: '已刷新',
      rawJson: null, syncedAt: '2026-07-28T13:00:00',
    })
    const wrapper = mount(AiAccountPanel)
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('刷新额度'))!.trigger('click')
    await flushPromises()

    expect(syncAiAccount).toHaveBeenCalledWith('aa-1')
    expect(fetchAiAccounts).toHaveBeenCalledTimes(2)
  })

  it('查看额度能解析 Codex 主次限额窗口和秒级重置时间', async () => {
    fetchAiAccounts.mockResolvedValue([account({
      provider: 'codex', label: '公共 Codex', plan: 'plus', quotaWindow: 'weekly_all', quotaPercent: 63,
      quotaRawJson: JSON.stringify({
        plan_type: 'plus',
        rate_limit: {
          primary_window: { used_percent: 35, limit_window_seconds: 18_000, reset_at: 1_785_241_650 },
          secondary_window: { used_percent: 63, limit_window_seconds: 604_800, reset_at: 1_785_822_360 },
        },
      }),
    })])
    const wrapper = mount(AiAccountPanel)
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('查看额度'))!.trigger('click')

    const details = wrapper.find('.quota-details')
    expect(details.text()).toContain('Plus')
    expect(details.text()).toContain('五小时会话额度')
    expect(details.text()).toContain('已用 35%')
    expect(details.text()).toContain('七天额度')
    expect(details.text()).toContain('已用 63%')
    expect(details.text()).not.toContain('1970')
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

    await wrapper.findAll('button').find((button) => button.text().includes('查看额度'))!.trigger('click')
    expect(wrapper.find('.quota-details').text()).toContain('Qoder 暂无公开的额度查询 API')
  })

  it('点登录后打开授权页并显示回填授权码的输入框', async () => {
    fetchAiAccounts.mockResolvedValue([])
    startAiAccountLogin.mockResolvedValue({
      state: 'st-1',
      authorizeUrl: 'https://claude.com/cai/oauth/authorize?x=1',
      expiresAt: '2026-07-28T13:40:00',
      hint: '完成授权后复制授权码',
      flow: 'authorization_code', userCode: null, pollIntervalSeconds: 0,
    })

    const wrapper = mount(AiAccountPanel)
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().includes('使用 Claude 账号登录'))!.trigger('click')
    await flushPromises()

    expect(window.open).toHaveBeenCalledWith(
      'https://claude.com/cai/oauth/authorize?x=1', '_blank', 'noopener')
    expect(wrapper.find('.login-actions input').exists()).toBe(true)
    expect(wrapper.text()).toContain('完成授权后复制授权码')
  })

  it('授权码为空时不调用完成登录接口', async () => {
    fetchAiAccounts.mockResolvedValue([])
    startAiAccountLogin.mockResolvedValue({
      state: 'st-1', authorizeUrl: 'https://example.invalid/a', expiresAt: '', hint: '',
      flow: 'authorization_code', userCode: null, pollIntervalSeconds: 0,
    })

    const wrapper = mount(AiAccountPanel)
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().includes('使用 Claude 账号登录'))!.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find((b) => b.text().includes('完成登录'))!.trigger('click')
    await flushPromises()

    expect(completeAiAccountLogin).not.toHaveBeenCalled()
  })

  it('Codex 使用设备码并自动查询，不再要求回填 localhost 地址', async () => {
    fetchAiAccounts.mockResolvedValue([])
    startAiAccountLogin.mockResolvedValue({
      state: 'st-codex',
      authorizeUrl: 'https://auth.openai.com/codex/device',
      expiresAt: '2026-07-28T13:40:00',
      hint: '在 OpenAI 官方页面登录并确认下方一次性短码',
      flow: 'device_code', userCode: 'ABCD-EFGH', pollIntervalSeconds: 300,
    })
    fetchAiAccountLoginStatus.mockResolvedValue({ status: 'pending', message: '等待授权', account: null })
    const wrapper = mount(AiAccountPanel)
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('使用 Codex 账号登录'))!.trigger('click')
    await flushPromises()

    expect(startAiAccountLogin).toHaveBeenCalledWith('codex')
    expect(window.open).toHaveBeenCalledWith('https://auth.openai.com/codex/device', '_blank', 'noopener')
    expect(wrapper.find('.login-actions input').exists()).toBe(false)
    expect(wrapper.find('.device-code').text()).toContain('ABCD-EFGH')
    expect(wrapper.text()).not.toContain('localhost')

    await wrapper.findAll('button').find((button) => button.text().includes('立即检查'))!.trigger('click')
    await flushPromises()
    expect(fetchAiAccountLoginStatus).toHaveBeenCalledWith('st-codex')
    wrapper.unmount()
  })

  it('自定义 API 表单提供 Base URL、Responses 模型和 API Key', async () => {
    fetchAiAccounts.mockResolvedValue([])
    const wrapper = mount(AiAccountPanel)
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('添加自定义 API'))!.trigger('click')

    expect(wrapper.find('input[placeholder="https://proxy.example.com/v1"]').exists()).toBe(true)
    expect(wrapper.find('input[placeholder="gpt-5.4"]').exists()).toBe(true)
    expect(wrapper.find('input[type="password"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('OpenAI Responses')
  })
})
