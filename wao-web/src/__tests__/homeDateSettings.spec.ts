import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import HomeDateReminderSettings from '@/components/settings/HomeDateReminderSettings.vue'
import { useSessionStore } from '@/stores/session'
const api = vi.hoisted(() => ({ fetchHomeDateSettings: vi.fn(), saveHomeDateSettings: vi.fn() }))
vi.mock('@/api/homeDateReminders', () => api)
beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  useSessionStore().accept({
    accountId: 'a1',
    member: { id: 'wg', name: '验收', initial: '验', color: '#4358D6', role: '成员' },
    identities: [],
    createdAt: '2026-10-06',
  })
  api.fetchHomeDateSettings
    .mockReset()
    .mockResolvedValue({ enabled: true, solarTerms: true, revision: 0 })
  api.saveHomeDateSettings.mockReset()
})
describe('首页日期设置草稿与冲突', () => {
  it('保存失败保留成员草稿，重新进入后恢复，再成功清理', async () => {
    let wrapper = mount(HomeDateReminderSettings)
    await flushPromises()
    await wrapper.get('input[aria-label="首页轻提醒"]').setValue(false)
    api.saveHomeDateSettings.mockRejectedValueOnce(new Error('offline'))
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '保存首页提醒设置')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('保存失败')
    expect(JSON.parse(localStorage.getItem('wao-home-date-settings-draft:wg')!)).toEqual({
      enabled: false,
      solarTerms: true,
      revision: 0,
    })
    wrapper.unmount()
    wrapper = mount(HomeDateReminderSettings)
    await flushPromises()
    expect(
      (wrapper.get('input[aria-label="首页轻提醒"]').element as HTMLInputElement).checked,
    ).toBe(false)
    api.saveHomeDateSettings.mockResolvedValueOnce({
      enabled: false,
      solarTerms: true,
      revision: 1,
    })
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '保存首页提醒设置')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('已保存')
    expect(localStorage.getItem('wao-home-date-settings-draft:wg')).toBeNull()
    wrapper.unmount()
  })
  it('409 不覆盖草稿，明确采用新版本后才允许重试保存', async () => {
    const wrapper = mount(HomeDateReminderSettings)
    await flushPromises()
    await wrapper.get('input[aria-label="首页轻提醒"]').setValue(false)
    api.saveHomeDateSettings.mockRejectedValueOnce({ response: { status: 409 } })
    api.fetchHomeDateSettings.mockResolvedValueOnce({
      enabled: true,
      solarTerms: false,
      revision: 2,
    })
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '保存首页提醒设置')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('本机选择仍保留')
    expect(
      (wrapper.get('input[aria-label="首页轻提醒"]').element as HTMLInputElement).checked,
    ).toBe(false)
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '保留草稿，采用最新版本')!
      .trigger('click')
    api.saveHomeDateSettings.mockResolvedValueOnce({
      enabled: false,
      solarTerms: true,
      revision: 3,
    })
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '保存首页提醒设置')!
      .trigger('click')
    await flushPromises()
    expect(api.saveHomeDateSettings).toHaveBeenLastCalledWith({
      enabled: false,
      solarTerms: true,
      revision: 2,
    })
    wrapper.unmount()
  })
  it('切换成员不读取或覆盖上一位成员的草稿', async () => {
    const wrapper = mount(HomeDateReminderSettings)
    await flushPromises()
    await wrapper.get('input[aria-label="首页轻提醒"]').setValue(false)
    useSessionStore().accept({
      accountId: 'a2',
      member: { id: 'other', name: '另一成员', initial: '另', color: '#4358D6', role: '成员' },
      identities: [],
      createdAt: '2026-10-06',
    })
    await flushPromises()
    expect(
      (wrapper.get('input[aria-label="首页轻提醒"]').element as HTMLInputElement).checked,
    ).toBe(true)
    expect(localStorage.getItem('wao-home-date-settings-draft:wg')).not.toBeNull()
    expect(localStorage.getItem('wao-home-date-settings-draft:other')).toBeNull()
    wrapper.unmount()
  })
})
