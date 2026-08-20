import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import WebhookConfigPanel from '@/components/settings/WebhookConfigPanel.vue'
import {
  createWebhookEndpoint,
  deleteWebhookEndpoint,
  fetchWebhookEndpoints,
  updateWebhookEndpoint,
  type FeishuWebhookEndpoint,
} from '@/api/system'

vi.mock('@/api/system', () => ({
  fetchWebhookEndpoints: vi.fn<() => Promise<unknown>>(),
  createWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  updateWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  deleteWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  testWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
}))

const existing: FeishuWebhookEndpoint = {
  id: 'fw1',
  name: 'WAO 主群',
  description: '系统与项目通知',
  urlMask: 'https://open.feishu.cn/open-apis/bot/v2/hook/••••1234',
  urlSet: true,
  enabled: true,
  systemDefault: true,
  createdAt: '2026-08-20T10:00:00',
  updatedAt: '2026-08-20T10:00:00',
}

describe('通用飞书 Webhook 配置', () => {
  const originalConfirm = window.confirm

  beforeEach(() => {
    vi.clearAllMocks()
    window.confirm = vi.fn<(message?: string) => boolean>(() => true)
  })

  afterEach(() => {
    window.confirm = originalConfirm
  })

  it('读取列表时只展示服务端脱敏地址', async () => {
    vi.mocked(fetchWebhookEndpoints).mockResolvedValue([existing])

    const wrapper = mount(WebhookConfigPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('WAO 主群')
    expect(wrapper.text()).toContain('••••1234')
    expect(wrapper.text()).not.toContain('secret-token')
    expect(wrapper.get('input[type="password"]').element.getAttribute('value') ?? '').toBe('')
  })

  it('空列表可新增，第一项明确作为系统默认配置', async () => {
    vi.mocked(fetchWebhookEndpoints).mockResolvedValue([])
    vi.mocked(createWebhookEndpoint).mockResolvedValue(existing)
    const wrapper = mount(WebhookConfigPanel)
    await flushPromises()

    await wrapper.get('button.primary.small').trigger('click')
    await wrapper.get('input[placeholder="例如 WAO 主群"]').setValue('WAO 主群')
    await wrapper.get('textarea').setValue('系统与项目通知')
    await wrapper.get('input[type="password"]').setValue(
      'https://open.feishu.cn/open-apis/bot/v2/hook/secret-token-1234',
    )
    await wrapper.get('form.editor').trigger('submit')
    await flushPromises()

    expect(createWebhookEndpoint).toHaveBeenCalledWith({
      name: 'WAO 主群',
      description: '系统与项目通知',
      url: 'https://open.feishu.cn/open-apis/bot/v2/hook/secret-token-1234',
      enabled: true,
      systemDefault: true,
    })
    expect(wrapper.text()).toContain('系统默认')
  })

  it('编辑名称时不要求前端取回或重传旧 Webhook 明文', async () => {
    vi.mocked(fetchWebhookEndpoints).mockResolvedValue([existing])
    vi.mocked(updateWebhookEndpoint).mockResolvedValue({ ...existing, name: 'WAO 验收群' })
    const wrapper = mount(WebhookConfigPanel)
    await flushPromises()

    await wrapper.get('input[placeholder="例如 WAO 主群"]').setValue('WAO 验收群')
    await wrapper.get('form.editor').trigger('submit')
    await flushPromises()

    expect(updateWebhookEndpoint).toHaveBeenCalledWith('fw1', {
      name: 'WAO 验收群',
      description: '系统与项目通知',
      url: undefined,
      enabled: true,
      systemDefault: true,
    })
  })

  it('离开编辑页前保护尚未保存且包含密钥输入的内存草稿', async () => {
    vi.mocked(fetchWebhookEndpoints).mockResolvedValue([existing])
    window.confirm = vi.fn<(message?: string) => boolean>(() => false)
    const wrapper = mount(WebhookConfigPanel)
    await flushPromises()

    await wrapper.get('input[type="password"]').setValue(
      'https://open.feishu.cn/open-apis/bot/v2/hook/not-yet-saved',
    )

    expect((wrapper.vm as unknown as { confirmLeave: () => boolean }).confirmLeave()).toBe(false)
    expect(window.confirm).toHaveBeenCalledOnce()
  })

  it('删除后重新读取服务端提升的默认配置状态', async () => {
    vi.mocked(fetchWebhookEndpoints)
      .mockResolvedValueOnce([existing])
      .mockResolvedValueOnce([])
    vi.mocked(deleteWebhookEndpoint).mockResolvedValue()
    const wrapper = mount(WebhookConfigPanel)
    await flushPromises()

    await wrapper.get('.icon-button.danger').trigger('click')
    await flushPromises()

    expect(deleteWebhookEndpoint).toHaveBeenCalledWith('fw1')
    expect(fetchWebhookEndpoints).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('还没有机器人配置')
  })

  it('删除默认项后的刷新失败会保留列表并提供可见重试', async () => {
    const secondary = {
      ...existing,
      id: 'fw2',
      name: '备用群',
      systemDefault: false,
      updatedAt: '2026-08-19T10:00:00',
    }
    vi.mocked(fetchWebhookEndpoints)
      .mockResolvedValueOnce([existing, secondary])
      .mockRejectedValueOnce(new Error('最新默认状态读取失败'))
      .mockResolvedValueOnce([{ ...secondary, systemDefault: true }])
    vi.mocked(deleteWebhookEndpoint).mockResolvedValue()
    const wrapper = mount(WebhookConfigPanel)
    await flushPromises()

    await wrapper.findAll('.icon-button.danger')[0]!.trigger('click')
    await flushPromises()

    expect(wrapper.get('.inline-load-error').text()).toContain('当前列表可能不是最新默认状态')
    expect(wrapper.text()).toContain('备用群')
    expect(wrapper.find('.name-line em').exists()).toBe(false)

    await wrapper.get('.inline-load-error button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.inline-load-error').exists()).toBe(false)
    expect(wrapper.get('.name-line em').text()).toBe('系统默认')
    expect(fetchWebhookEndpoints).toHaveBeenCalledTimes(3)
  })

  it('编辑 A 的未保存草稿不会因删除 B 而静默丢失', async () => {
    const secondary = {
      ...existing,
      id: 'fw2',
      name: '备用群',
      systemDefault: false,
      updatedAt: '2026-08-19T10:00:00',
    }
    vi.mocked(fetchWebhookEndpoints).mockResolvedValue([existing, secondary])
    window.confirm = vi.fn<(message?: string) => boolean>(() => false)
    const wrapper = mount(WebhookConfigPanel)
    await flushPromises()

    await wrapper.get('input[placeholder="例如 WAO 主群"]').setValue('A 的未保存名称')
    await wrapper.findAll('.icon-button.danger')[1]!.trigger('click')
    await flushPromises()

    expect(window.confirm).toHaveBeenCalledOnce()
    expect(deleteWebhookEndpoint).not.toHaveBeenCalled()
    expect((wrapper.get('input[placeholder="例如 WAO 主群"]').element as HTMLInputElement).value)
      .toBe('A 的未保存名称')
  })
})
