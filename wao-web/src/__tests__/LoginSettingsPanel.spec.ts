import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import LoginSettingsPanel from '@/components/settings/LoginSettingsPanel.vue'
import {
  fetchWechatOfficialLoginConfig,
  saveWechatOfficialLoginConfig,
  type WechatOfficialLoginConfig,
} from '@/api/system'

vi.mock('@/api/system', () => ({
  fetchWechatOfficialLoginConfig: vi.fn<() => Promise<WechatOfficialLoginConfig>>(),
  saveWechatOfficialLoginConfig: vi.fn<() => Promise<WechatOfficialLoginConfig>>(),
}))

const emptyConfig: WechatOfficialLoginConfig = {
  enabled: false,
  appId: '',
  appSecretMask: '',
  appSecretSet: false,
  tokenMask: '',
  tokenSet: false,
  configured: false,
  callbackPath: '/api/auth/wechat/official/callback',
}

describe('系统登录配置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchWechatOfficialLoginConfig).mockResolvedValue(emptyConfig)
  })

  it('从空配置录入真实公众号凭据并只展示脱敏保存结果', async () => {
    vi.mocked(saveWechatOfficialLoginConfig).mockResolvedValue({
      ...emptyConfig,
      enabled: true,
      appId: 'wx-system',
      appSecretMask: 'syst••••••cret',
      appSecretSet: true,
      tokenMask: 'call••••••oken',
      tokenSet: true,
      configured: true,
    })
    const wrapper = mount(LoginSettingsPanel)
    await flushPromises()
    const inputs = wrapper.findAll('.fields input')

    await inputs[0]!.setValue('wx-system')
    await inputs[1]!.setValue('system-secret')
    await inputs[2]!.setValue('callback-token')
    await wrapper.get('button.toggle').trigger('click')
    await wrapper.get('footer button').trigger('submit')
    await flushPromises()

    expect(saveWechatOfficialLoginConfig).toHaveBeenCalledWith({
      enabled: true,
      appId: 'wx-system',
      appSecret: 'system-secret',
      token: 'callback-token',
    })
    expect(wrapper.text()).toContain('公众号登录配置完整')
    expect((inputs[1]!.element as HTMLInputElement).value).toBe('')
    expect(inputs[1]!.attributes('placeholder')).toContain('syst••••••cret')
    expect(wrapper.text()).not.toContain('system-secret')
  })

  it('保存失败时保留全部草稿并显示重试入口', async () => {
    vi.mocked(saveWechatOfficialLoginConfig).mockRejectedValue(new Error('配置服务暂时不可用'))
    const wrapper = mount(LoginSettingsPanel)
    await flushPromises()
    const inputs = wrapper.findAll('.fields input')

    await inputs[0]!.setValue('wx-draft')
    await inputs[1]!.setValue('draft-secret')
    await inputs[2]!.setValue('draft-token')
    await wrapper.get('footer button').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('配置服务暂时不可用')
    expect(wrapper.text()).toContain('重试保存')
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('wx-draft')
    expect((inputs[1]!.element as HTMLInputElement).value).toBe('draft-secret')
  })

  it('读取失败时不显示伪配置并可重新读取', async () => {
    vi.mocked(fetchWechatOfficialLoginConfig).mockRejectedValueOnce(new Error('读取失败'))
    const wrapper = mount(LoginSettingsPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('读取失败')
    expect(wrapper.text()).toContain('重新读取')
    expect(wrapper.find('.config-card').exists()).toBe(false)
  })
})
