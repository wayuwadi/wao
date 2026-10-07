import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ReleaseHighlightsHost from '@/components/release/ReleaseHighlightsHost.vue'
import { releaseJustUpdated } from '@/composables/releaseUpdateState'
import { consumePendingReleaseVersion, rememberPendingReleaseVersion, rememberReleaseReload } from '@/utils/versionUpdate'
const { experience, session } = vi.hoisted(() => ({ experience: vi.fn(), session: { authed: true, userId: 'member-a' } }))
vi.mock('@/composables/featureExperience', () => ({ useFeatureExperience: () => experience }))
vi.mock('@/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))
let wrapper: ReturnType<typeof mount>
beforeEach(() => {
  sessionStorage.clear()
  releaseJustUpdated.value = false
  experience.mockReset().mockResolvedValue(true)
})
afterEach(() => {
  vi.restoreAllMocks()
  wrapper?.unmount()
  document.body.innerHTML = ''
})
describe('更新后的轻提示', () => {
  it('加载目标制品后兑现一次具体入口，再次挂载不重复跳转', async () => {
    const script = document.createElement('script')
    script.setAttribute('data-v', 'new1234'); document.body.append(script)
    rememberReleaseReload('new1234', 'member-a', { featureId: 'settings', action: { target: 'settings-about', label: '关于 WAO' } })
    wrapper = mount(ReleaseHighlightsHost); await flushPromises()
    expect(experience).toHaveBeenCalledWith('settings', {target:'settings-about',label:'关于 WAO'})
    wrapper.unmount(); wrapper = mount(ReleaseHighlightsHost); await flushPromises()
    expect(experience).toHaveBeenCalledOnce()
  })
  it('会话存储被禁用时仍保持页面可用，不伪造已更新',()=>{
    vi.spyOn(Storage.prototype,'getItem').mockImplementation(()=>{throw new Error('blocked')})
    wrapper=mount(ReleaseHighlightsHost)
    expect(releaseJustUpdated.value).toBe(false)
  })
  it('安全刷新且匹配当前制品只显示已更新状态，不自动弹窗', () => {
    const script = document.createElement('script')
    script.setAttribute('data-v', 'abcdef0')
    document.body.append(script)
    rememberPendingReleaseVersion('abcdef0')
    wrapper = mount(ReleaseHighlightsHost)
    expect(releaseJustUpdated.value).toBe(true)
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(consumePendingReleaseVersion()).toBe('')
  })
  it('普通刷新或尚未加载目标制品不能冒充已更新', () => {
    const script = document.createElement('script')
    script.setAttribute('data-v', 'old')
    document.body.append(script)
    rememberPendingReleaseVersion('new')
    wrapper = mount(ReleaseHighlightsHost)
    expect(releaseJustUpdated.value).toBe(false)
    wrapper.unmount()
    wrapper = mount(ReleaseHighlightsHost)
    expect(releaseJustUpdated.value).toBe(false)
    expect(document.querySelector('[role="dialog"]')).toBeNull()
  })
})
