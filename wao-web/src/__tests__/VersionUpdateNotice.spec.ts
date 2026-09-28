import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import VersionUpdateNotice from '@/components/ui/VersionUpdateNotice.vue'
import { reloadToLatestVersion, WEB_UPDATE_EVENT } from '@/utils/versionUpdate'

vi.mock('@/utils/versionUpdate', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/utils/versionUpdate')>()
  return { ...original, reloadToLatestVersion: vi.fn<(version?: string) => void>() }
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

describe('新版本升级提示', () => {
  it('收到版本事件后仅显示右上角通知，保留输入焦点，由用户决定更新', async () => {
    const wrapper = mount(VersionUpdateNotice, { attachTo: document.body })
    const input = document.createElement('textarea')
    input.value = '尚未保存的记录'
    document.body.append(input)
    input.focus()

    document.body.dispatchEvent(
      new CustomEvent(WEB_UPDATE_EVENT, { detail: { version: 'abc12345' } }),
    )
    await nextTick()

    const notice = document.body.querySelector<HTMLElement>('.version-update-notice')!
    expect(notice.getAttribute('role')).toBe('status')
    expect(notice.textContent).toContain('发现新版本')
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    expect(document.activeElement).toBe(input)
    expect(input.value).toBe('尚未保存的记录')
    expect(reloadToLatestVersion).not.toHaveBeenCalled()

    notice.querySelector('button')!.click()
    expect(reloadToLatestVersion).toHaveBeenCalledWith('abc12345')
    wrapper.unmount()
  })

  it('重复版本事件不会叠加通知，空版本事件不会误报', async () => {
    const wrapper = mount(VersionUpdateNotice, { attachTo: document.body })
    const notify = (version?: string) =>
      document.body.dispatchEvent(new CustomEvent(WEB_UPDATE_EVENT, { detail: { version } }))

    notify('new-version')
    await nextTick()
    notify('new-version')
    notify('')
    await nextTick()
    expect(document.body.querySelectorAll('.version-update-notice')).toHaveLength(1)
    wrapper.unmount()
  })

  it('组件卸载后不再响应版本事件', async () => {
    const wrapper = mount(VersionUpdateNotice, { attachTo: document.body })
    wrapper.unmount()

    document.body.dispatchEvent(
      new CustomEvent(WEB_UPDATE_EVENT, { detail: { version: 'after-unmount' } }),
    )
    await nextTick()

    expect(document.body.querySelector('.version-update-notice')).toBeNull()
  })
})
