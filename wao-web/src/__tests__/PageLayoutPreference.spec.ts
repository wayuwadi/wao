import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import PageLayoutPreference from '@/components/settings/PageLayoutPreference.vue'
import { usePreferenceStore } from '@/stores/preferences'
import { useSessionStore } from '@/stores/session'
import { readPageLayoutAttempt, rememberPageLayoutAttempt } from '@/utils/pageLayout'

const api = vi.hoisted(() => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
vi.mock('@/api/favorites', () => api)

describe('页面布局偏好', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    const session = useSessionStore()
    session.account = { accountId: 'a', member: { id: 'a', name: '测试成员', initial: 'A', color: '', role: '成员' }, identities: [], createdAt: '' }
    usePreferenceStore().prepareForMember('a')
    api.fetchPreference.mockResolvedValue('')
    api.savePreference.mockResolvedValue(undefined)
  })

  it('显示默认选择，保存时禁止重复选择，保存成功后才切换布局', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()
    let finish!: () => void
    api.savePreference.mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
    const wrapper = mount(PageLayoutPreference)
    const choices = wrapper.findAll('[role="radio"]')
    expect(choices).toHaveLength(3)
    expect(choices[0]!.attributes('aria-checked')).toBe('true')
    await choices[2]!.trigger('click')
    expect(wrapper.get('.layout-status').text()).toBe('保存中…')
    expect(choices.every(choice => choice.attributes('disabled') !== undefined)).toBe(true)
    expect(preferences.pageLayoutStyle).toBe('unified')
    finish()
    await flushPromises()
    expect(preferences.pageLayoutStyle).toBe('centered')
    expect(wrapper.get('.layout-status').text()).toBe('已保存')
    expect(readPageLayoutAttempt('a')).toBeNull()
    wrapper.unmount()
  })

  it('失败保留布局与成员待保存选择，重新打开后可以重试而不自动提交', async () => {
    const preferences = usePreferenceStore()
    await preferences.load()
    api.savePreference.mockRejectedValueOnce(new Error('网络不可用'))
    const first = mount(PageLayoutPreference)
    await first.findAll('[role="radio"]')[1]!.trigger('click')
    await flushPromises()
    expect(preferences.pageLayoutStyle).toBe('unified')
    expect(first.get('[role="alert"]').text()).toContain('网络不可用')
    expect(readPageLayoutAttempt('a')).toBe('fluid')
    expect(readPageLayoutAttempt('b')).toBeNull()
    first.unmount()
    const second = mount(PageLayoutPreference)
    expect(api.savePreference).toHaveBeenCalledTimes(1)
    expect(second.get('[role="alert"]').text()).toContain('上次选择')
    await second.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(api.savePreference).toHaveBeenLastCalledWith('page-layout-style-v1', 'fluid')
    expect(preferences.pageLayoutStyle).toBe('fluid')
    expect(readPageLayoutAttempt('a')).toBeNull()
    second.unmount()
  })

  it('偏好读取失败不能选择，重新读取后恢复真实选择', async () => {
    const preferences = usePreferenceStore()
    api.fetchPreference.mockRejectedValueOnce(new Error('读取失败'))
    await preferences.load()
    const wrapper = mount(PageLayoutPreference)
    expect(wrapper.find('[role="radiogroup"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('读取失败')
    api.fetchPreference.mockImplementation(key => Promise.resolve(key === 'page-layout-style-v1' ? 'centered' : ''))
    await wrapper.get('[role="alert"] button').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[role="radio"]')[2]!.attributes('aria-checked')).toBe('true')
    wrapper.unmount()
  })

  it('浏览器不能保留选择时仍保留失败重试，且不假报草稿已存', async () => {
    await usePreferenceStore().load()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('不可写入') })
    api.savePreference.mockRejectedValueOnce(new Error('网络不可用'))
    const wrapper = mount(PageLayoutPreference)
    await wrapper.findAll('[role="radio"]')[2]!.trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('浏览器暂不能保留')
    expect(wrapper.text()).toContain('重试保存')
    expect(usePreferenceStore().pageLayoutStyle).toBe('unified')
    wrapper.unmount()
  })

  it('未知或损坏待保存值不恢复为一个可提交选择', () => {
    localStorage.setItem('wao:page-layout-pending-v1:a', 'unexpected')
    expect(readPageLayoutAttempt('a')).toBeNull()
    rememberPageLayoutAttempt('b', 'fluid')
    expect(readPageLayoutAttempt('a')).toBeNull()
    expect(readPageLayoutAttempt('b')).toBe('fluid')
  })
})
