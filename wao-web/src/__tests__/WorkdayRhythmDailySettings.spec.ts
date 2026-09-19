import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import WorkdayRhythmDailySettings from '@/components/workday/WorkdayRhythmDailySettings.vue'
import {
  saveRhythmSettings,
  deleteRhythmChecklistItem,
  fetchRhythmChecklistItems,
  fetchRhythmSettings,
  updateRhythmChecklistItem,
  type RhythmChecklistItem,
} from '@/api/workdayRhythm'
import { toast } from '@/composables/toast'

vi.mock('@/api/workdayRhythm', async (importOriginal) => {
  const original = await importOriginal<typeof import('@/api/workdayRhythm')>()
  return {
    ...original,
    createRhythmChecklistItem: vi.fn<typeof original.createRhythmChecklistItem>(),
    deleteRhythmChecklistItem: vi.fn<typeof original.deleteRhythmChecklistItem>(),
    fetchRhythmChecklistItems: vi.fn<typeof original.fetchRhythmChecklistItems>(),
    fetchRhythmSettings: vi.fn<typeof original.fetchRhythmSettings>(),
    saveRhythmSettings: vi.fn<typeof original.saveRhythmSettings>(),
    updateRhythmChecklistItem: vi.fn<typeof original.updateRhythmChecklistItem>(),
  }
})

vi.mock('@/api/system', () => ({
  fetchFeishuChats: vi.fn<() => Promise<never[]>>().mockResolvedValue([]),
  fetchWebhookEndpoints: vi.fn<() => Promise<never[]>>().mockResolvedValue([]),
}))

vi.mock('@/composables/toast', () => ({
  toast: vi.fn<(message: string, icon?: string) => void>(),
}))

const plan: RhythmChecklistItem = {
  id: 'plan',
  kind: 'PLAN',
  category: 'WORK',
  title: '制定今日计划',
  itemType: 'CHECK',
  durationMinutes: null,
  scheduleType: 'WORKDAY',
  weekdays: [],
  startTime: '07:00',
  dueTime: '09:00',
  dueNextDay: false,
  active: true,
  order: 10,
  notifyDirect: false,
  notifyChat: false,
  chatId: null,
  chatName: null,
  notifyWebhook: false,
  webhookId: null,
  webhookName: null,
  reminderTime: null,
  reminderNextDay: false,
}

const custom: RhythmChecklistItem = {
  ...plan,
  id: 'custom',
  kind: 'CUSTOM',
  category: 'LIFE',
  title: '整理桌面',
  scheduleType: 'DAILY',
  startTime: '18:00',
  dueTime: '22:00',
  order: 20,
}

async function mountSettings() {
  const wrapper = mount(WorkdayRhythmDailySettings)
  await flushPromises()
  return wrapper
}

describe('WorkdayRhythmDailySettings', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(fetchRhythmSettings).mockResolvedValue({
      followAlternateWeek: true,
      documentKbId: null,
      documentFolderId: null,
      documentTemplateId: null,
      documentTitlePattern: '{date} 每日工作记录',
      tianrenEnabled: true,
      tianrenRequired: false,
    })
    vi.mocked(fetchRhythmChecklistItems).mockResolvedValue([
      structuredClone(plan),
      structuredClone(custom),
    ])
    vi.mocked(updateRhythmChecklistItem).mockImplementation(async (id, body) => ({
      ...(id === plan.id ? plan : custom),
      ...body,
      id,
    }))
    vi.mocked(deleteRhythmChecklistItem).mockResolvedValue()
  })

  it('默认只展示项目摘要，并且一次只展开一个项目', async () => {
    const wrapper = await mountSettings()

    expect(wrapper.findAll('.item-summary')).toHaveLength(2)
    expect(wrapper.find('.item-detail').exists()).toBe(false)
    expect(wrapper.text()).toContain('工作日 · 07:00–09:00 · 不提醒')

    await wrapper.findAll('.edit-item')[0]!.trigger('click')
    expect(wrapper.findAll('.item-detail')).toHaveLength(1)
    expect(wrapper.get('.item-title-field input').element).toHaveProperty('value', '制定今日计划')

    await wrapper.findAll('.edit-item')[1]!.trigger('click')
    expect(wrapper.findAll('.item-detail')).toHaveLength(1)
    expect(wrapper.get('.item-title-field input').element).toHaveProperty('value', '整理桌面')
  })

  it('移除系统计划项时立即持久化停用状态，并可原位恢复', async () => {
    const wrapper = await mountSettings()

    await wrapper.get('[aria-label="移除制定今日计划"]').trigger('click')
    await flushPromises()

    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('系统联动和历史记录会保留'))
    expect(updateRhythmChecklistItem).toHaveBeenCalledWith(
      'plan',
      expect.objectContaining({ active: false }),
    )
    expect(wrapper.get('.item-editor.inactive').text()).toContain('制定今日计划')
    expect(wrapper.get('.item-editor.inactive').text()).toContain('已停用')
    expect(toast).toHaveBeenCalledWith('已从每日必做中移除“制定今日计划”', 'ri-check-line')

    await wrapper.get('[aria-label="恢复制定今日计划"]').trigger('click')
    await flushPromises()
    expect(updateRhythmChecklistItem).toHaveBeenLastCalledWith(
      'plan',
      expect.objectContaining({ active: true }),
    )
    expect(wrapper.find('.item-editor.inactive').exists()).toBe(false)
  })

  it('移除失败时保留项目并展示服务端错误，自定义项目仍走删除接口', async () => {
    vi.mocked(updateRhythmChecklistItem).mockRejectedValueOnce(new Error('网络暂时不可用'))
    const wrapper = await mountSettings()

    await wrapper.get('[aria-label="移除制定今日计划"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('网络暂时不可用')
    expect(wrapper.find('.item-editor.inactive').exists()).toBe(false)

    await wrapper.get('[aria-label="删除整理桌面"]').trigger('click')
    await flushPromises()
    expect(deleteRhythmChecklistItem).toHaveBeenCalledWith('custom')
    expect(wrapper.text()).not.toContain('整理桌面')
  })

  it('自定义项目可选择固定分钟倒计时并持久化', async () => {
    const wrapper = await mountSettings()
    await wrapper.findAll('.edit-item')[1]!.trigger('click')
    const selects = wrapper.findAll('.item-fields select')
    await selects[0]!.setValue('COUNTDOWN')
    await wrapper.get('input[type="number"]').setValue(20)
    await wrapper.get('footer .primary').trigger('click')
    await flushPromises()

    expect(updateRhythmChecklistItem).toHaveBeenCalledWith(
      'custom',
      expect.objectContaining({ itemType: 'COUNTDOWN', durationMinutes: 20 }),
    )
  })

  it('保存设置后已切换成员，不继续保存旧清单', async () => {
    const wrapper = mount(WorkdayRhythmDailySettings)
    await flushPromises()
    let finish!: () => void
    vi.mocked(saveRhythmSettings).mockReturnValueOnce(new Promise<Awaited<ReturnType<typeof saveRhythmSettings>>>(resolve => { finish = () => resolve(wrapper.vm.form!) }))
    const pending = wrapper.vm.submit()
    useSessionStore().account = { member: { id: 'another-member' } } as never
    finish()
    await pending
    expect(updateRhythmChecklistItem).not.toHaveBeenCalled()
    expect(wrapper.emitted('saved')).toBeUndefined()
    wrapper.unmount()
  })

})
