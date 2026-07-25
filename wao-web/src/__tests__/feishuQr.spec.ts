import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/feishu', () => ({
  getFeishuAuthorizationUrl: vi.fn<() => Promise<string>>().mockResolvedValue(
    'https://passport.feishu.cn/suite/passport/oauth/authorize?client_id=test',
  ),
}))

import { mountFeishuQr } from '@/utils/feishuQr'

describe('飞书二维码', () => {
  afterEach(() => {
    document.body.replaceChildren()
    delete window.QRLogin
  })

  it('创建 iframe 前移除加载占位，避免二维码被裁掉', async () => {
    const container = document.createElement('div')
    container.id = 'feishu-qr'
    container.innerHTML = '<div class="qr-loading">加载中</div>'
    document.body.appendChild(container)

    window.QRLogin = vi.fn<NonNullable<Window['QRLogin']>>((options) => {
      const frame = document.createElement('iframe')
      document.getElementById(options.id)?.appendChild(frame)
      return { matchOrigin: () => true }
    })

    const unmount = await mountFeishuQr('feishu-qr')

    expect(container.querySelector('.qr-loading')).toBeNull()
    expect(container.querySelectorAll('iframe')).toHaveLength(1)
    expect(container.querySelector('iframe')?.title).toBe('飞书扫码登录')

    unmount()
    expect(container.querySelector('iframe')).toBeNull()
  })
})
