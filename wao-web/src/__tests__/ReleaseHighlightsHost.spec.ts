import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import ReleaseHighlightsHost from '@/components/release/ReleaseHighlightsHost.vue'
import { CURRENT_RELEASE } from '@/data/featureCatalog'
import { fetchPreference, savePreference } from '@/api/favorites'
import { openReleaseHighlights, rememberPendingReleaseVersion } from '@/utils/versionUpdate'

vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))

function render() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/features', component: { template: '<div />' } },
    ],
  })
  return mount(ReleaseHighlightsHost, { global: { plugins: [router, createPinia()] } })
}

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  sessionStorage.clear()
  vi.mocked(fetchPreference).mockReset().mockResolvedValue('')
  vi.mocked(savePreference).mockReset().mockResolvedValue()
})
afterEach(() => {
  document.body.innerHTML = ''
  vi.clearAllMocks()
})

describe('更新介绍触发与已读状态', () => {
  it('普通首次打开不弹，完成安全更新后才自动展示', async () => {
    const ordinary = render()
    await flushPromises()
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    ordinary.unmount()

    rememberPendingReleaseVersion('abcdef0')
    const updated = render()
    await flushPromises()
    expect(document.body.textContent).toContain(CURRENT_RELEASE.title)
    updated.unmount()
  })

  it('跨设备偏好已读时不重复自动展示', async () => {
    rememberPendingReleaseVersion('abcdef0')
    vi.mocked(fetchPreference).mockResolvedValue(
      JSON.stringify({ lastSeenReleaseId: CURRENT_RELEASE.id }),
    )
    const wrapper = render()
    await flushPromises()
    expect(document.body.querySelector('[role="dialog"]')).toBeNull()
    wrapper.unmount()
  })

  it('可从设置手动重开，并在关闭时保存成员级已读状态', async () => {
    const wrapper = render()
    openReleaseHighlights()
    await flushPromises()
    document.body.querySelector<HTMLButtonElement>('.close')!.click()
    await flushPromises()
    expect(savePreference).toHaveBeenCalledWith(
      'release-highlights-state-v1',
      expect.stringContaining(CURRENT_RELEASE.id),
    )
    wrapper.unmount()
  })
})
