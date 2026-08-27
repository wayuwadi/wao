import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import VersionUpdateNotice from '@/components/ui/VersionUpdateNotice.vue'
import { reloadToLatestVersion, WEB_UPDATE_EVENT } from '@/utils/versionUpdate'

vi.mock('@/utils/versionUpdate', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/utils/versionUpdate')>()
  return { ...original, reloadToLatestVersion: vi.fn<() => void>() }
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

describe('新版本升级提示', () => {
  it('收到真实版本事件后展示提示并由用户刷新', async () => {
    const wrapper = mount(VersionUpdateNotice, { attachTo: document.body })

    document.body.dispatchEvent(
      new CustomEvent(WEB_UPDATE_EVENT, { detail: { version: 'abc12345' } }),
    )
    await nextTick()

    const dialog = document.body.querySelector<HTMLElement>('[role="dialog"]')!
    expect(dialog.textContent).toContain('版本更新')
    expect(dialog.textContent).toContain('检测到新版本已发布')
    expect(document.activeElement?.textContent?.trim()).toBe('立即刷新')

    document.body.querySelector<HTMLButtonElement>('.version-update-refresh')!.click()
    expect(reloadToLatestVersion).toHaveBeenCalledOnce()
    wrapper.unmount()
  })

  it('稍后提醒会隐藏同一版本，空版本事件不会误报', async () => {
    const wrapper = mount(VersionUpdateNotice, { attachTo: document.body })
    const notify = (version?: string) =>
      document.body.dispatchEvent(new CustomEvent(WEB_UPDATE_EVENT, { detail: { version } }))

    notify('new-version')
    await nextTick()
    document.body.querySelector<HTMLButtonElement>('.version-update-later')!.click()
    await nextTick()
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()

    notify('new-version')
    notify('')
    await nextTick()
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    wrapper.unmount()
  })

  it('组件卸载后不再响应版本事件', async () => {
    const wrapper = mount(VersionUpdateNotice, { attachTo: document.body })
    wrapper.unmount()

    document.body.dispatchEvent(
      new CustomEvent(WEB_UPDATE_EVENT, { detail: { version: 'after-unmount' } }),
    )
    await nextTick()

    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
  })
})
