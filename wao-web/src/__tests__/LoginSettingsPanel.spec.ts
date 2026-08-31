import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import LoginSettingsPanel from '@/components/settings/LoginSettingsPanel.vue'
import {
  fetchWechatOfficialLoginConfig,
  saveWechatOfficialLoginConfig,
  verifyWechatOfficialLoginConfig,
  type WechatOfficialLoginConfig,
} from '@/api/system'

vi.mock('@/api/system', () => ({
  fetchWechatOfficialLoginConfig: vi.fn<() => Promise<WechatOfficialLoginConfig>>(),
  saveWechatOfficialLoginConfig: vi.fn<() => Promise<WechatOfficialLoginConfig>>(),
  verifyWechatOfficialLoginConfig: vi.fn<() => Promise<{ ready: boolean; message: string }>>(),
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
    vi.mocked(verifyWechatOfficialLoginConfig).mockResolvedValue({
      ready: true,
      message: '微信接口验证通过，登录页可以获取真实公众号二维码',
    })
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
    expect(verifyWechatOfficialLoginConfig).toHaveBeenCalledOnce()
    expect(wrapper.text()).toContain('公众号登录已通过真实接口验证')
    expect((inputs[1]!.element as HTMLInputElement).value).toBe('')
    expect(inputs[1]!.attributes('placeholder')).toContain('syst••••••cret')
    expect(wrapper.text()).not.toContain('system-secret')
  })

  it('配置非空但出口 IP 未进白名单时展示微信返回的可执行诊断', async () => {
    vi.mocked(fetchWechatOfficialLoginConfig).mockResolvedValue({
      ...emptyConfig,
      enabled: true,
      appId: 'wx-system',
      appSecretSet: true,
      tokenSet: true,
      configured: true,
    })
    vi.mocked(verifyWechatOfficialLoginConfig).mockRejectedValue(
      new Error('微信拒绝服务器出口 IP 171.83.123.164，请将它加入公众号后台的 IP 白名单（微信错误码 40164）'),
    )

    const wrapper = mount(LoginSettingsPanel)
    await flushPromises()

    expect(wrapper.text()).toContain('171.83.123.164')
    expect(wrapper.text()).toContain('IP 白名单')
    expect(wrapper.text()).toContain('错误码 40164')
    expect(wrapper.text()).not.toContain('登录页可获取真实二维码')
    expect(wrapper.text()).toContain('重新验证')
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
