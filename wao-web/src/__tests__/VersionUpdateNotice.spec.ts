import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import VersionUpdateNotice from '@/components/ui/VersionUpdateNotice.vue'
import { fetchWebRelease } from '@/api/version'
import { CURRENT_RELEASE } from '@/data/featureCatalog'
import {
  reloadToLatestVersion,
  WEB_UPDATE_EVENT,
  reportWorkspaceReloadState,
} from '@/utils/versionUpdate'

vi.mock('@/api/version', () => ({ fetchWebRelease: vi.fn() }))
vi.mock('@/utils/versionUpdate', async (original) => ({
  ...(await original<typeof import('@/utils/versionUpdate')>()),
  reloadToLatestVersion: vi.fn(),
}))
let wrapper: VueWrapper
const upcoming = { ...CURRENT_RELEASE, title: '目标版本的更新内容' }
const notify = (version: unknown) =>
  document.body.dispatchEvent(new CustomEvent(WEB_UPDATE_EVENT, { detail: { version } }))
const click = async (selector: string) => {
  document.body.querySelector<HTMLButtonElement>(selector)!.click()
  await nextTick()
}
async function render() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/features', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  wrapper = mount(VersionUpdateNotice, { attachTo: document.body, global: { plugins: [router] } })
  return router
}
beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
    },
  )
  vi.mocked(fetchWebRelease)
    .mockReset()
    .mockResolvedValue({ version: 'new1234', release: upcoming })
})
afterEach(() => {
  wrapper?.unmount()
  document.body.innerHTML = ''
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe('顶栏更新入口', () => {
  it('已在历史锚点时，重复点击仍定位时间线且关闭浮层', async () => {
    const router = await render()
    await router.push('/features#recent-updates')
    const section = document.createElement('section')
    section.id = 'recent-updates'
    section.scrollIntoView = vi.fn()
    document.body.append(section)
    for (let index = 0; index < 2; index++) {
      await click('.update-entry')
      await click('a[href="/features#recent-updates"]')
      await flushPromises()
      expect(document.querySelector('[role="dialog"]')).toBeNull()
    }
    expect(section.scrollIntoView).toHaveBeenCalledTimes(2)
    expect(router.currentRoute.value.hash).toBe('#recent-updates')
  })
  it('新版本不抢焦点、不自动展开；展开才读取目标说明，稍后可重开', async () => {
    await render()
    const input = document.createElement('textarea')
    input.value = '草稿'
    document.body.append(input)
    input.focus()
    notify('new1234')
    notify('new1234')
    notify('')
    notify(123)
    await nextTick()
    expect(document.activeElement).toBe(input)
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    expect(fetchWebRelease).not.toHaveBeenCalled()
    expect(document.querySelectorAll('.update-entry')).toHaveLength(1)
    await click('.update-entry')
    await flushPromises()
    expect(document.body.textContent).toContain(upcoming.title)
    expect(document.body.textContent).not.toContain(CURRENT_RELEASE.title)
    expect(document.querySelector('[aria-label^="去体验"]')).toBeNull()
    await click('.later')
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(document.querySelector('.update-entry')?.textContent).toContain('有更新')
    await click('.update-entry')
    expect(fetchWebRelease).toHaveBeenCalledOnce()
    expect(input.value).toBe('草稿')
  })
  it('当前制品说明提供真实功能入口', async () => {
    const router = await render()
    await click('.update-entry')
    expect(document.body.textContent).toContain(CURRENT_RELEASE.title)
    await click('[aria-label^="去体验"]')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/features')
    expect(document.querySelector('[role="dialog"]')).toBeNull()
  })
  it('缺少说明时显示空状态，保存屏障失败可重试且不丢失目标版本', async () => {
    vi.mocked(fetchWebRelease).mockResolvedValue({ version: 'new1234', release: null })
    await render()
    notify('new1234')
    await click('.update-entry')
    await flushPromises()
    expect(document.body.textContent).toContain('暂未附带更新说明')
    await click('.update-button')
    expect(reloadToLatestVersion).toHaveBeenCalledWith('new1234')
    reportWorkspaceReloadState({ state: 'saving' })
    await nextTick()
    expect(document.querySelector<HTMLButtonElement>('.update-button')!.disabled).toBe(true)
    reportWorkspaceReloadState({ state: 'failed', message: '保存失败，草稿已保留' })
    await nextTick()
    expect(document.body.textContent).toContain('保存失败，草稿已保留')
    await click('.update-button')
    expect(reloadToLatestVersion).toHaveBeenCalledTimes(2)
  })
  it('版本错配不展示说明，重试后恢复', async () => {
    vi.mocked(fetchWebRelease).mockResolvedValueOnce({ version: 'old', release: upcoming })
    await render()
    notify('new1234')
    await click('.update-entry')
    await flushPromises()
    expect(document.body.textContent).toContain('版本正在切换')
    expect(document.body.textContent).not.toContain(upcoming.title)
    await click('.text-button')
    await flushPromises()
    expect(document.body.textContent).toContain(upcoming.title)
  })
  it('连续版本事件使旧请求失效，不覆盖后到的新版本内容', async () => {
    let resolveOld!: (value: Awaited<ReturnType<typeof fetchWebRelease>>) => void
    vi.mocked(fetchWebRelease).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolveOld = resolve
        }),
    )
    await render()
    notify('old')
    await click('.update-entry')
    notify('new1234')
    await flushPromises()
    resolveOld({ version: 'old', release: { ...upcoming, title: '过期说明' } })
    await flushPromises()
    expect(document.body.textContent).toContain(upcoming.title)
    expect(document.body.textContent).not.toContain('过期说明')
  })
  it('Esc 恢复焦点，点击外部关闭，卸载后清除监听', async () => {
    await render()
    await click('.update-entry')
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    await nextTick()
    expect(document.activeElement).toBe(document.querySelector('.update-entry'))
    await click('.update-entry')
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await nextTick()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    wrapper.unmount()
    notify('new1234')
    await nextTick()
    expect(document.querySelector('.update-entry')).toBeNull()
  })
})
