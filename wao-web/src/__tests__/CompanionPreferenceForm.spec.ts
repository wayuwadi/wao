import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, expect, it, vi } from 'vitest'
import CompanionPreferenceForm from '@/components/capabilities/CompanionPreferenceForm.vue'
import { useSessionStore } from '@/stores/session'
import { fetchCompanion, saveCompanionMode } from '@/api/noteAiCompanion'
vi.mock('@/api/noteAiCompanion', () => ({ fetchCompanion: vi.fn(), saveCompanionMode: vi.fn() }))
const value = {
  available: true,
  message: '',
  queued: 0,
  running: 0,
  unread: 0,
  mode: 'AUTO' as const,
  version: 1,
  capabilityVersion: 1,
}
beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  setActivePinia(createPinia())
  useSessionStore().accept({
    accountId: 'synthetic',
    member: { id: 'synthetic', name: '合成', initial: 'M', color: '', role: '' },
    identities: [],
    createdAt: '',
  })
  vi.mocked(fetchCompanion).mockResolvedValue(value)
})
it('刷新恢复版本化草稿，冲突需明确处理后才保存', async () => {
  let wrapper = mount(CompanionPreferenceForm)
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((item) => item.text() === '先看建议')!
    .trigger('click')
  wrapper.unmount()
  vi.mocked(fetchCompanion).mockResolvedValue({ ...value, version: 2 })
  wrapper = mount(CompanionPreferenceForm)
  await flushPromises()
  const save = wrapper.findAll('button').find((item) => item.text() === '保存采用方式')!
  expect(save.attributes('disabled')).toBeDefined()
  expect(
    wrapper
      .findAll('button')
      .find((item) => item.text() === '先看建议')!
      .attributes('aria-pressed'),
  ).toBe('true')
  expect(wrapper.text()).toContain('账号已更新')
  await wrapper
    .findAll('button')
    .find((item) => item.text() === '保留草稿，基于最新版本')!
    .trigger('click')
  vi.mocked(saveCompanionMode).mockResolvedValue({ mode: 'REVIEW', version: 3 })
  await save.trigger('click')
  await flushPromises()
  expect(saveCompanionMode).toHaveBeenCalledWith('REVIEW', 2)
  expect(localStorage.getItem('wao-companion-mode-draft-v1:synthetic')).toBeNull()
  wrapper.unmount()
})
it('保存时发生冲突不会卡在保存中，确认新版本后可以重试', async () => {
  const wrapper = mount(CompanionPreferenceForm)
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((item) => item.text() === '先看建议')!
    .trigger('click')
  vi.mocked(saveCompanionMode).mockRejectedValueOnce({ response: { status: 409 } })
  vi.mocked(fetchCompanion).mockResolvedValue({ ...value, version: 4 })
  await wrapper
    .findAll('button')
    .find((item) => item.text() === '保存采用方式')!
    .trigger('click')
  await flushPromises()
  const keep = wrapper.findAll('button').find((item) => item.text() === '保留草稿，基于最新版本')!
  expect(keep.attributes('disabled')).toBeUndefined()
  await keep.trigger('click')
  vi.mocked(saveCompanionMode).mockResolvedValue({ mode: 'REVIEW', version: 5 })
  await wrapper
    .findAll('button')
    .find((item) => item.text() === '保存采用方式')!
    .trigger('click')
  await flushPromises()
  expect(saveCompanionMode).toHaveBeenLastCalledWith('REVIEW', 4)
  wrapper.unmount()
})
it('账号读取失败和重试不会丢掉已经保留的草稿及原版本', async () => {
  const key = 'wao-companion-mode-draft-v1:synthetic'
  localStorage.setItem(key, JSON.stringify({ mode: 'REVIEW', version: 1 }))
  vi.mocked(fetchCompanion).mockRejectedValueOnce(new Error('网络断开'))
  const wrapper = mount(CompanionPreferenceForm)
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((item) => item.text() === '重新读取')!
    .trigger('click')
  await flushPromises()
  expect(
    wrapper
      .findAll('button')
      .find((item) => item.text() === '先看建议')!
      .attributes('aria-pressed'),
  ).toBe('true')
  wrapper.unmount()
  expect(localStorage.getItem(key)).toContain('REVIEW')
})
