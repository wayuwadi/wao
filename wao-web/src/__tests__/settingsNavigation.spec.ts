import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'
import { usePreferenceStore } from '@/stores/preferences'
import { normalizeSettingsTab, settingsCategory } from '@/utils/settingsNavigation'
import { fetchNoteAiSettings, saveNoteAiSettings } from '@/api/noteAiSettings'
import { rememberNoteSettingsDraft } from '@/utils/noteAiSettingsDraft'
import { useSessionStore } from '@/stores/session'

const api = vi.hoisted(() => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
const mcp = vi.hoisted(() => ({ fetchWorkspaceMcpTokens: vi.fn().mockResolvedValue([]), createWorkspaceMcpToken: vi.fn(), revokeWorkspaceMcpToken: vi.fn() }))
vi.mock('@/api/workspaceMcp', () => mcp)
vi.mock('@/api/favorites', () => api)
vi.mock('@/api/noteAiSettings', () => ({
  fetchNoteAiSettings: vi.fn(),
  saveNoteAiSettings: vi.fn(),
}))
vi.mock('@/api/codexModels', () => ({
  fetchCodexModels: vi
    .fn()
    .mockResolvedValue({
      models: [{ id: 'gpt-6-astra', label: 'GPT-6-Astra', reasoningEfforts: ['medium', 'high'] }],
    }),
}))
vi.mock('@/api/noteDiscoveries', () => ({
  fetchDiscoveryPreference: vi
    .fn()
    .mockResolvedValue({ enabled: false, available: true, version: 'v1' }),
  fetchDiscoveryCurrent: vi.fn().mockResolvedValue({ automationState: 'IDLE' }),
  saveDiscoveryPreference: vi.fn(),
  discoveryHttpStatus: vi.fn(),
}))
const savedAi = () => ({
  version: 'v1',
  configured: true,
  availability: '',
  settings: {
    title: false,
    polish: true,
    image: false,
    executionMode: 'parallel' as const,
    maxConcurrent: 3 as const,
    model: '',
    reasoningEffort: 'medium',
    titlePrompt: '',
    polishPrompt: '',
    imagePrompt: '',
    titleEpoch: 1,
    imageEpoch: 1,
    polishEpoch: 0,
  },
})

describe('常用优先的设置分类', () => {
  let wrapper: VueWrapper, router: Router
  beforeEach(async () => {
    localStorage.clear()
    setActivePinia(createPinia())
    rememberNoteSettingsDraft(useSessionStore().currentUser.id, null)
    vi.clearAllMocks()
    api.fetchPreference.mockResolvedValue('')
    api.savePreference.mockResolvedValue(undefined)
    vi.mocked(fetchNoteAiSettings).mockResolvedValue(savedAi())
    vi.mocked(saveNoteAiSettings).mockResolvedValue(savedAi())
    await usePreferenceStore().load()
    router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/settings', component: { template: '<div />' } },
        { path: '/other', component: { template: '<div />' } },
      ],
    })
    await router.push('/settings')
    await router.isReady()
    wrapper = mount(SettingsView, { global: { plugins: [router] } })
  })
  afterEach(() => {
    wrapper.unmount()
    vi.restoreAllMocks()
  })

  async function notes() {
    await wrapper
      .findAll('.snav-item')
      .find((button) => button.text() === '小记与 AI')!
      .trigger('click')
    await flushPromises()
  }

  it('默认展示三个常用控件，管理与模型设置不抢占首页', () => {
    expect(wrapper.get('.snav-item[aria-current="page"]').text()).toBe('常用设置')
    expect(wrapper.findAll('.pref-name').map((row) => row.text())).toEqual([
      '主题',
      '安静陪伴',
      '庆祝动画',
    ])
    expect(wrapper.findAll('.snav-item')).toHaveLength(7)
    expect(wrapper.find('.note-ai-settings-form').exists()).toBe(false)
    expect(fetchNoteAiSettings).not.toHaveBeenCalled()
  })

  it('设置不再提供 CLI 和 MCP 弹层入口', async () => {
    await router.replace('/settings?tab=management')
    await flushPromises()
    expect(wrapper.text()).not.toContain('WAO CLI 与 MCP')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(mcp.createWorkspaceMcpToken).not.toHaveBeenCalled()
  })

  it.each([
    ['prefs', 'appearance', 'appearance'],
    ['quick-links', 'navigation', 'personal'],
    ['pet', 'pet', 'appearance'],
    ['members', 'members', 'management'],
    ['notifications', 'notification-policy', 'reminders'],
    ['invalid', 'common', 'common'],
    ['network-outage', 'members', 'management'],
    ['version', 'members', 'management'],
    ['wao-ai-capabilities', 'members', 'management'],
  ])('兼容旧链接 %s', (old, panel, category) => {
    expect(normalizeSettingsTab(old)).toBe(panel)
    expect(settingsCategory(panel)).toBe(category)
  })

  it('旧天人通知锚点准确映射，分类切换会清除旧锚点', async () => {
    expect(normalizeSettingsTab('notifications', '#tianren-notifications')).toBe(
      'tianren-notifications',
    )
    await router.replace('/settings?tab=prefs#tianren-notifications')
    await wrapper
      .findAll('.snav-item')
      .find((button) => button.text() === '常用设置')!
      .trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/settings')
  })

  it('提醒干净表单随最新配置刷新，未保存草稿不被刷新覆盖', async () => {
    await notes()
    const preferences = usePreferenceStore()
    preferences.noteFullscreenReminder = { enabled: true, delayMinutes: 3 }
    await flushPromises()
    expect(wrapper.get<HTMLSelectElement>('[aria-label="小记全屏提醒间隔"]').element.value).toBe(
      '3',
    )
    await wrapper.get('[aria-label="小记全屏提醒间隔"]').setValue(5)
    preferences.noteFullscreenReminder = { enabled: false, delayMinutes: 10 }
    await flushPromises()
    expect(wrapper.get<HTMLSelectElement>('[aria-label="小记全屏提醒间隔"]').element.value).toBe(
      '5',
    )
    expect(wrapper.get('.note-reminder-preferences').text()).toContain('有未保存修改')
  })

  it('AI 与提醒独立保存，失败保留提醒草稿并可重试', async () => {
    await notes()
    const reminder = wrapper.get('[aria-label="小记全屏提醒间隔"]')
    await reminder.setValue(5)
    const ai = wrapper.get('.note-ai-settings-form')
    await ai.get('footer button.primary').trigger('click')
    await flushPromises()
    expect(saveNoteAiSettings).toHaveBeenCalledOnce()
    expect(api.savePreference).not.toHaveBeenCalled()
    expect(wrapper.get<HTMLSelectElement>('[aria-label="小记全屏提醒间隔"]').element.value).toBe(
      '5',
    )
    api.savePreference.mockRejectedValueOnce(new Error('网络不可用'))
    await wrapper.get('.note-reminder-preferences .preference-actions button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.note-reminder-preferences').text()).toContain('网络不可用')
    expect(wrapper.get<HTMLSelectElement>('[aria-label="小记全屏提醒间隔"]').element.value).toBe(
      '5',
    )
    await wrapper
      .findAll('.note-reminder-preferences button')
      .find((button) => button.text() === '重试保存')!
      .trigger('click')
    await flushPromises()
    expect(usePreferenceStore().noteFullscreenReminder.delayMinutes).toBe(5)
    expect(wrapper.get('.note-reminder-preferences').text()).toContain('已保存')
  })

  it('提醒草稿取消离开后保留，重建页面可恢复而不在进入时误确认', async () => {
    await notes()
    await wrapper.get('[aria-label="小记全屏提醒间隔"]').setValue(5)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    await router.push('/other')
    expect(router.currentRoute.value.path).toBe('/settings')
    expect(confirm).toHaveBeenCalledOnce()
    wrapper.unmount()
    confirm.mockClear()
    wrapper = mount(SettingsView, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.get<HTMLSelectElement>('[aria-label="小记全屏提醒间隔"]').element.value).toBe(
      '5',
    )
    expect(confirm).not.toHaveBeenCalled()
    expect(api.savePreference).not.toHaveBeenCalled()
  })

  it('小记使用分项设置，AI 草稿切换分类再回来仍保留', async () => {
    await notes()
    expect(wrapper.get('[aria-label="设置项目"] button[aria-pressed="true"]').text()).toBe(
      'AI 能力',
    )
    await wrapper.get('.note-ai-settings-form input').setValue(true)
    await wrapper
      .findAll('.snav-item')
      .find((button) => button.text() === '常用设置')!
      .trigger('click')
    await flushPromises()
    await notes()
    expect(wrapper.get<HTMLInputElement>('.note-ai-settings-form input').element.checked).toBe(true)
    expect(saveNoteAiSettings).not.toHaveBeenCalled()
  })

  it('浏览器无法保留提醒草稿时明确提示，保存过程中阻止离开', async () => {
    await notes()
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('存储受限')
    })
    await wrapper.get('[aria-label="小记全屏提醒间隔"]').setValue(3)
    expect(wrapper.get('.note-reminder-preferences').text()).toContain('浏览器暂不能保留提醒草稿')
    let resolveSave!: () => void
    api.savePreference.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          resolveSave = resolve
        }),
    )
    await wrapper.get('.note-reminder-preferences .preference-actions button').trigger('click')
    await router.push('/other')
    expect(router.currentRoute.value.path).toBe('/settings')
    resolveSave()
    await flushPromises()
    expect(usePreferenceStore().noteFullscreenReminder.delayMinutes).toBe(3)
  })
})
