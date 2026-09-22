import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import DeepSeekPolicyPanel from '@/components/settings/DeepSeekPolicyPanel.vue'
const api = vi.hoisted(() => ({ fetch: vi.fn(), save: vi.fn() }))
vi.mock('@/api/deepSeekPolicy', () => ({ fetchDeepSeekPolicy: api.fetch, saveDeepSeekPolicy: api.save }))
const policy = { enabled: true, dailyTokenLimit: 10000000, polishEnabled: true, assistantEnabled: false, tianrenEnabled: false, backgroundEnabled: false }
beforeEach(() => {
  vi.resetAllMocks()
  sessionStorage.clear()
  api.fetch.mockResolvedValue({ policy: { ...policy }, day: '2026-09-22', usage: [] })
})
it('restores drafts before refreshing usage and never overwrites pending edits', async () => {
  sessionStorage.setItem('wao:deepseek-policy-draft', JSON.stringify({ ...policy, dailyTokenLimit: 9000000 }))
  const wrapper = mount(DeepSeekPolicyPanel)
  await flushPromises()
  expect((wrapper.get('input[type=number]').element as HTMLInputElement).value).toBe('9000000')
  await wrapper.get('input[type=number]').setValue(8000000)
  await wrapper.get('.policy-heading button').trigger('click')
  await flushPromises()
  expect((wrapper.get('input[type=number]').element as HTMLInputElement).value).toBe('8000000')
  wrapper.unmount()
})
it('preserves failed saves and retries the submitted configuration', async () => {
  const wrapper = mount(DeepSeekPolicyPanel)
  await flushPromises()
  await wrapper.get('input[type=number]').setValue(9000000)
  api.save.mockRejectedValueOnce(new Error('服务暂不可用')).mockResolvedValueOnce({ policy: { ...policy, dailyTokenLimit: 9000000 }, day: '2026-09-22', usage: [] })
  await wrapper.get('form').trigger('submit')
  await flushPromises()
  expect(wrapper.get('[role=alert]').text()).toContain('服务暂不可用')
  expect(sessionStorage.getItem('wao:deepseek-policy-draft')).toContain('9000000')
  await wrapper.get('[role=alert] button').trigger('click')
  await flushPromises()
  expect(api.save).toHaveBeenLastCalledWith({ ...policy, dailyTokenLimit: 9000000 })
  expect(wrapper.text()).toContain('已保存')
  expect(sessionStorage.getItem('wao:deepseek-policy-draft')).toBeNull()
  wrapper.unmount()
})
