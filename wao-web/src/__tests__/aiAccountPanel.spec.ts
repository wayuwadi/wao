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
  it('仅提供服务端 API 添加，不再登录或导入 Runner 凭据', async () => {
    fetchAiAccounts.mockResolvedValue([])
    const wrapper = mount(AiAccountPanel)
    await flushPromises()
    expect(wrapper.text()).not.toMatch(/使用 Codex 账号登录|使用 Claude 账号登录|导入凭据|添加自定义 API/)
    expect(wrapper.text()).toContain('添加 DeepSeek')
    expect(startAiAccountLogin).not.toHaveBeenCalled()
  })

  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('open', vi.fn())
    sessionStorage.clear()
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





  it('DeepSeek 配置可选择官方模型并以密码框管理 Token', async () => {
    fetchAiAccounts.mockResolvedValue([])
    createAiAccount.mockResolvedValue({})
    const wrapper = mount(AiAccountPanel)
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('添加 DeepSeek'))!.trigger('click')

    const form = wrapper.find('.account-form')
    expect(form.text()).toContain('DeepSeek V4 Flash（快速）')
    expect(form.text()).toContain('DeepSeek V4 Pro（质量优先）')
    expect(form.find('input[value="https://api.deepseek.com"]').attributes('readonly')).toBeDefined()
    const token = form.find('input[type="password"]')
    await token.setValue('token-input')
    await flushPromises()
    expect(sessionStorage.getItem('wao:ai-account-form-draft')).not.toContain('token-input')
    await form.find('select').setValue('deepseek-v4-pro')
    await form.trigger('submit')
    await flushPromises()

    expect(createAiAccount).toHaveBeenCalledWith(expect.objectContaining({
      provider: 'deepseek',
      baseUrl: 'https://api.deepseek.com',
      model: 'deepseek-v4-pro',
      apiProtocol: 'chat_completions',
      apiKey: 'token-input',
    }))
  })

  it('模型网关固定可信地址、允许编辑模型且不会把 Token 写入草稿', async () => {
    fetchAiAccounts.mockResolvedValue([])
    createAiAccount.mockResolvedValue({})
    const wrapper = mount(AiAccountPanel)
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text().includes('添加模型网关'))!.trigger('click')

    const form = wrapper.find('.account-form')
    expect(form.text()).toContain('模型标识')
    const model = form.find('input[placeholder="例如 xingshi"]')
    expect((model.element as HTMLInputElement).value).toBe('xingshi')
    expect(form.text()).toContain('不会进入 AI Run')
    const endpoint = form.find(
      'input[value="https://ai.xingshiapp.cn/api/model-credentials/litellm-proxy/v1"]',
    )
    expect(endpoint.attributes('readonly')).toBeDefined()
    const token = form.find('input[type="password"]')
    await token.setValue('gateway-token-input')
    await flushPromises()
    expect(sessionStorage.getItem('wao:ai-account-form-draft')).not.toContain('gateway-token-input')
    await form.trigger('submit')
    await flushPromises()

    expect(createAiAccount).toHaveBeenCalledWith(expect.objectContaining({
      provider: 'model_gateway',
      baseUrl: 'https://ai.xingshiapp.cn/api/model-credentials/litellm-proxy/v1',
      model: 'xingshi',
      apiProtocol: 'chat_completions',
      apiKey: 'gateway-token-input',
    }))
  })

  it('编辑已有模型网关时把成员填写的模型原样保存', async () => {
    fetchAiAccounts.mockResolvedValue([account({
      provider: 'model_gateway', label: '醒市模型网关', email: null,
      authMethod: 'api_key',
      baseUrl: 'https://ai.xingshiapp.cn/api/model-credentials/litellm-proxy/v1',
      model: 'deepseek-v4-flash', apiProtocol: 'chat_completions', quotaPercent: null,
    })])
    updateAiAccount.mockResolvedValue({})
    const wrapper = mount(AiAccountPanel)
    await flushPromises()

    await wrapper.find('button[title="编辑"]').trigger('click')
    const form = wrapper.find('.account-form')
    await form.find('input[placeholder="例如 xingshi"]').setValue('xingshi')
    await form.trigger('submit')
    await flushPromises()

    expect(updateAiAccount).toHaveBeenCalledWith('aa-1', expect.objectContaining({
      provider: 'model_gateway',
      model: 'xingshi',
      apiKey: undefined,
    }))
  })

  it('已启用的最新 DeepSeek 配置明确标记为文档润色默认项', async () => {
    fetchAiAccounts.mockResolvedValue([
      account({
        id: 'ds-new', provider: 'deepseek', label: 'DeepSeek 文档润色', email: null,
        authMethod: 'api_key', baseUrl: 'https://api.deepseek.com', model: 'deepseek-v4-flash',
        apiProtocol: 'chat_completions', quotaPercent: null,
      }),
      account({
        id: 'ds-old', provider: 'deepseek', label: '备用 DeepSeek', email: null,
        authMethod: 'api_key', baseUrl: 'https://api.deepseek.com', model: 'deepseek-v4-pro',
        apiProtocol: 'chat_completions', quotaPercent: null,
      }),
    ])

    const wrapper = mount(AiAccountPanel)
    await flushPromises()

    const cards = wrapper.findAll('.account-card')
    const first = cards[0]
    const second = cards[1]
    expect(first).toBeDefined()
    expect(second).toBeDefined()
    if (!first || !second) throw new Error('应渲染两个 DeepSeek 配置')
    expect(first.find('.polish-default').text()).toBe('文档润色')
    expect(second.find('.polish-default').exists()).toBe(false)
    expect(first.text()).toContain('Chat Completions')
  })

  it('最新模型网关配置与 DeepSeek 共用唯一文档润色标记', async () => {
    fetchAiAccounts.mockResolvedValue([
      account({
        id: 'gateway-new', provider: 'model_gateway', label: '醒市模型网关', email: null,
        authMethod: 'api_key',
        baseUrl: 'https://ai.xingshiapp.cn/api/model-credentials/litellm-proxy/v1',
        model: 'deepseek-v4-flash', apiProtocol: 'chat_completions', quotaPercent: null,
      }),
      account({
        id: 'deepseek-old', provider: 'deepseek', label: 'DeepSeek 备用', email: null,
        authMethod: 'api_key', baseUrl: 'https://api.deepseek.com', model: 'deepseek-v4-pro',
        apiProtocol: 'chat_completions', quotaPercent: null,
      }),
    ])

    const wrapper = mount(AiAccountPanel)
    await flushPromises()

    const cards = wrapper.findAll('.account-card')
    expect(cards[0]?.text()).toContain('受信网关')
    expect(cards[0]?.find('.polish-default').text()).toBe('文档润色')
    expect(cards[1]?.find('.polish-default').exists()).toBe(false)
  })
})
