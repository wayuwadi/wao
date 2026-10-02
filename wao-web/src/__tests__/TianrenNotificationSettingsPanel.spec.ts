import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TianrenNotificationSettingsPanel from '@/components/settings/TianrenNotificationSettingsPanel.vue'
import { DEFAULT_WORKDAY_RHYTHM } from '@/utils/workdayRhythm'
const api = vi.hoisted(() => ({fetch: vi.fn(), save: vi.fn()}))
vi.mock('@/api/tianren', () => ({fetchTianrenPreference: api.fetch, saveTianrenPreference: api.save}))
vi.mock('@/stores/session', () => ({useSessionStore: () => ({userId: 'member-a'})}))
const stub = { props: ['modelValue'], emits: ['update:modelValue'], template: `<button class="change" @click="$emit('update:modelValue', {...modelValue, notifyTianren: true})">change</button>` }
const render = () => mount(TianrenNotificationSettingsPanel, {global: {stubs: {WorkdayNotificationRoutes: stub}}})
describe('卦象通知独立设置', () => {
  beforeEach(() => { localStorage.clear(); vi.clearAllMocks(); api.fetch.mockResolvedValue(JSON.stringify(DEFAULT_WORKDAY_RHYTHM)); api.save.mockResolvedValue(undefined) })
  it('通知设置失败保留本机草稿，重试成功后清理草稿并保留原模板', async () => {
    const w = render(); await flushPromises(); await w.get('.change').trigger('click')
    expect(localStorage.getItem('wao-tianren-notification-draft:member-a')).toContain('"notifyTianren":true')
    api.save.mockRejectedValueOnce(new Error('失败'))
    await w.findAll('.actions button')[1]!.trigger('click'); await flushPromises()
    expect(w.text()).toContain('失败'); expect(localStorage.getItem('wao-tianren-notification-draft:member-a')).toBeTruthy()
    await w.findAll('.actions button')[1]!.trigger('click'); await flushPromises()
    expect(w.text()).toContain('已保存'); expect(localStorage.getItem('wao-tianren-notification-draft:member-a')).toBeNull()
    const saved = JSON.parse(api.save.mock.calls[1]![1])
    expect(saved.blocks).toEqual(DEFAULT_WORKDAY_RHYTHM.blocks)
    expect(saved.energyNotification.notifyTianren).toBe(true); w.unmount()
  })
  it('其他页面更新服务端偏好后拒绝覆盖并保留草稿', async () => {
    const w = render(); await flushPromises(); await w.get('.change').trigger('click')
    api.fetch.mockResolvedValue('{"changed":true}')
    await w.findAll('.actions button')[1]!.trigger('click'); await flushPromises()
    expect(api.save).not.toHaveBeenCalled(); expect(w.text()).toContain('已在别处更新')
    expect(localStorage.getItem('wao-tianren-notification-draft:member-a')).toBeTruthy(); w.unmount()
  })
})
