import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import AccountResourcesView from '@/views/AccountResourcesView.vue'
import type {
  AccountResource,
  AccountResourceIntegration,
  RotatedIntegrationToken,
  SaveAccountResource,
  VerificationMessage,
} from '@/api/accountResources'

const api = vi.hoisted(() => ({
  fetchAccountResources: vi.fn<() => Promise<AccountResource[]>>(),
  createAccountResource: vi.fn<(body: SaveAccountResource) => Promise<AccountResource>>(),
  updateAccountResource:
    vi.fn<(id: string, body: SaveAccountResource) => Promise<AccountResource>>(),
  deleteAccountResource: vi.fn<(id: string, revision: number) => Promise<null>>(),
  revealAccountResourceSecret: vi.fn<() => Promise<{ secret: string | null }>>(),
  fetchVerificationMessages: vi.fn<() => Promise<VerificationMessage[]>>(),
  retryVerificationForward: vi.fn<(id: string) => Promise<VerificationMessage>>(),
  fetchAccountResourceIntegration: vi.fn<() => Promise<AccountResourceIntegration>>(),
  saveAccountResourceIntegration:
    vi.fn<(notificationWebhookId: string) => Promise<AccountResourceIntegration>>(),
  rotateAccountResourceToken:
    vi.fn<(type: 'ingest' | 'crawler') => Promise<RotatedIntegrationToken>>(),
}))

vi.mock('@/api/accountResources', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/accountResources')>()),
  ...api,
}))

vi.mock('@/api/system', () => ({
  fetchWebhookEndpoints: vi.fn<() => Promise<never[]>>(() => Promise.resolve([])),
}))

vi.mock('@/composables/toast', () => ({ toast: vi.fn<(message: string, icon?: string) => void>() }))

const integration: AccountResourceIntegration = {
  ingestTokenConfigured: false,
  crawlerTokenConfigured: false,
  notificationWebhookId: '',
  ingestPath: '/api/public/account-resources/messages',
  latestCodePath: '/api/public/account-resources/codes/latest?phone={手机号}',
}

function account(): AccountResource {
  return {
    id: 'ar1',
    platform: '小红书',
    accountLabel: '小红书运营号',
    loginIdentifier: '19945783940',
    phoneNumber: '19945783940',
    notes: '爬虫登录使用',
    enabled: true,
    hasSecret: true,
    revision: 2,
    createdBy: 'wg',
    updatedBy: 'wg',
    createdAt: '2026-09-01T09:00:00',
    updatedAt: '2026-09-01T09:30:00',
  }
}

function message(): VerificationMessage {
  return {
    id: 'vm1',
    phoneNumber: '19945783940',
    sender: '小红书',
    source: '主板机-1-103',
    message: '【小红书】您的验证码是：621059，5分钟内有效',
    code: '621059',
    receivedAt: '2026-09-01T09:35:50',
    forwardStatus: 'SENT',
    forwardError: '',
    accountLabels: ['小红书运营号'],
  }
}

describe('AccountResourcesView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.fetchAccountResources.mockResolvedValue([])
    api.fetchVerificationMessages.mockResolvedValue([])
    api.fetchAccountResourceIntegration.mockResolvedValue({ ...integration })
  })

  it('空库显示真实接入引导，不使用演示账号或验证码', async () => {
    const wrapper = mount(AccountResourcesView)
    await flushPromises()

    expect(wrapper.text()).toContain('还没有账号资源')
    expect(wrapper.text()).toContain('新增第一个真实账号')
    expect(wrapper.text()).not.toContain('621059')
  })

  it('保存失败时保留账号输入和本机草稿', async () => {
    api.createAccountResource.mockRejectedValueOnce(new Error('服务暂不可用'))
    const wrapper = mount(AccountResourcesView)
    await flushPromises()

    await wrapper.find('.state-card button').trigger('click')
    await wrapper.find('input[placeholder="例如：小红书"]').setValue('小红书')
    await wrapper.find('input[placeholder="便于团队识别"]').setValue('未保存运营号')
    await wrapper.find('form.editor').trigger('submit')
    await flushPromises()

    expect(wrapper.find('.inline-error').text()).toContain('服务暂不可用')
    expect(localStorage.getItem('wao-account-resource-draft:new')).toContain('未保存运营号')
    expect(wrapper.find('.editor .primary').text()).toContain('重试保存')
  })

  it('展示账号匹配后的真实验证码和飞书转发状态', async () => {
    api.fetchAccountResources.mockResolvedValueOnce([account()])
    api.fetchVerificationMessages.mockResolvedValueOnce([message()])
    const wrapper = mount(AccountResourcesView)
    await flushPromises()

    expect(wrapper.text()).toContain('小红书运营号')
    await wrapper.findAll('.tabs button')[1]!.trigger('click')

    expect(wrapper.text()).toContain('621059')
    expect(wrapper.text()).toContain('已转发')
    expect(wrapper.text()).toContain('主板机-1-103')
  })

  it('接入设置明确区分手机入站与爬虫只读令牌', async () => {
    const wrapper = mount(AccountResourcesView)
    await flushPromises()
    await wrapper.findAll('.tabs button')[2]!.trigger('click')

    expect(wrapper.text()).toContain('尚未生成入站令牌')
    expect(wrapper.text()).toContain('尚未生成爬虫令牌')
    expect(wrapper.text()).toContain('/api/public/account-resources/messages')
    expect(wrapper.text()).toContain('/api/public/account-resources/codes/latest?phone=手机号')
  })
})
