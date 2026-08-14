import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import IosInstallGuide from '@/components/IosInstallGuide.vue'
import * as iosInstall from '@/utils/iosInstall'

const displayWindow = (standalone: boolean) => ({
  matchMedia: vi.fn<(query: string) => MediaQueryList>(() => ({ matches: standalone } as MediaQueryList)),
})

describe('iOS 主屏幕应用识别', () => {
  it('识别 iPhone 和伪装成 Mac 的 iPadOS', () => {
    expect(iosInstall.isIosDevice({ userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0)', platform: 'iPhone', maxTouchPoints: 5 })).toBe(true)
    expect(iosInstall.isIosDevice({ userAgent: 'Mozilla/5.0 (Macintosh)', platform: 'MacIntel', maxTouchPoints: 5 })).toBe(true)
    expect(iosInstall.isIosDevice({ userAgent: 'Mozilla/5.0 (Macintosh)', platform: 'MacIntel', maxTouchPoints: 0 })).toBe(false)
  })

  it('已从主屏幕独立运行时不再提供安装提示', () => {
    const phone = { userAgent: 'iPhone', platform: 'iPhone', maxTouchPoints: 5, standalone: true }
    expect(iosInstall.shouldOfferIosInstall(phone, displayWindow(false))).toBe(false)
    expect(iosInstall.shouldOfferIosInstall({ ...phone, standalone: false }, displayWindow(true))).toBe(false)
  })
})

describe('iOS 安装引导', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('逐步说明分享与添加到主屏幕，并允许永久关闭本版提示', async () => {
    vi.spyOn(iosInstall, 'shouldOfferIosInstall').mockReturnValue(true)
    const wrapper = mount(IosInstallGuide)
    await flushPromises()

    expect(wrapper.text()).toContain('项目、AI 运行和自动化')
    await wrapper.get('.install-details-button').trigger('click')
    expect(wrapper.text()).toContain('添加到主屏幕')
    await wrapper.get('.install-done-button').trigger('click')
    expect(wrapper.find('.install-guide').exists()).toBe(false)
    expect(localStorage.getItem(iosInstall.IOS_INSTALL_DISMISSED_KEY)).toBe('dismissed')
  })
})

describe('Web App 资源清单', () => {
  it('声明独立窗口、iOS 元信息及三个真实业务入口', () => {
    const index = readFileSync(join(process.cwd(), 'index.html'), 'utf8')
    const manifest = JSON.parse(readFileSync(join(process.cwd(), 'public/manifest.webmanifest'), 'utf8'))

    expect(index).toContain('rel="manifest" href="/manifest.webmanifest"')
    expect(index).toContain('apple-mobile-web-app-capable')
    expect(index).toContain('apple-mobile-web-app-status-bar-style')
    expect(manifest.display).toBe('standalone')
    expect(manifest.icons.map((icon: { sizes: string }) => icon.sizes)).toEqual(['192x192', '512x512'])
    expect(manifest.shortcuts.map((shortcut: { url: string }) => shortcut.url)).toEqual([
      '/projects',
      '/?console=runs#ai-launcher',
      '/automation',
    ])
  })
})
