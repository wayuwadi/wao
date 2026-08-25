import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'
import { useNavStore } from '@/stores/nav'
import {
  fetchBuiltinMenuRegistry,
  fetchExternalAppRegistry,
  saveExternalAppRegistry,
} from '@/api/system'
import {
  DEFAULT_BUILTIN_MENUS,
  DEFAULT_EXTERNAL_APPS,
  externalAppItemId,
  type BuiltinMenuRegistry,
  type ExternalAppRegistry,
} from '@/config/nav'
import { fetchPreference, savePreference } from '@/api/favorites'
import { useQuickLinks } from '@/composables/quickLinks'

vi.mock('@/api/system', () => ({
  fetchExternalAppRegistry: vi.fn<() => Promise<ExternalAppRegistry>>(),
  saveExternalAppRegistry: vi.fn<(apps: ExternalAppRegistry['apps']) => Promise<ExternalAppRegistry>>(),
  fetchBuiltinMenuRegistry: vi.fn<() => Promise<BuiltinMenuRegistry>>(),
  saveBuiltinMenuRegistry: vi.fn<() => Promise<BuiltinMenuRegistry>>(),
  fetchWebhookConfig: vi.fn<() => Promise<unknown>>(),
  saveWebhookConfig: vi.fn<() => Promise<unknown>>(),
  testWebhook: vi.fn<() => Promise<void>>(),
  fetchWebhookEndpoints: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
  createWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  updateWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  deleteWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  testWebhookEndpoint: vi.fn<() => Promise<unknown>>(),
  fetchSiteInfo: vi.fn<() => Promise<unknown>>(),
  saveSiteInfo: vi.fn<() => Promise<unknown>>(),
}))

vi.mock('@/api/favorites', async (importOriginal) => ({
  ...await importOriginal<typeof import('@/api/favorites')>(),
  fetchPreference: vi.fn<() => Promise<string>>(),
  savePreference: vi.fn<() => Promise<void>>(),
}))

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/:p(.*)*', component: { template: '<div/>' } }],
})

async function mountRegistrySettings() {
  const wrapper = mount(SettingsView, { global: { plugins: [router] } })
  const entry = wrapper.findAll('.snav-item').find((button) => button.text() === '菜单配置')
  await entry!.trigger('click')
  await flushPromises()
  return wrapper
}

describe('外部应用菜单注册表设置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(fetchExternalAppRegistry).mockResolvedValue({
      version: 1,
      apps: DEFAULT_EXTERNAL_APPS,
    })
    vi.mocked(fetchBuiltinMenuRegistry).mockResolvedValue({
      version: 1,
      menus: DEFAULT_BUILTIN_MENUS,
    })
    vi.mocked(fetchPreference).mockResolvedValue('')
    vi.mocked(savePreference).mockResolvedValue(undefined)
  })

  it('读取注册表并展示现有 Agent 记录', async () => {
    const wrapper = await mountRegistrySettings()

    expect(fetchExternalAppRegistry).toHaveBeenCalledOnce()
    expect(wrapper.find('.registry-list').text()).toContain('自媒体 Agent')
    expect(wrapper.find('.registry-list').text()).toContain('media-agent')
    expect(wrapper.find('.registry-editor').text()).toContain('/apps/media-agent')
  })

  it('注册新应用后立即更新动态菜单 store', async () => {
    const dataStudio = {
      id: 'data-studio', label: '数据工作台', icon: 'ri-dashboard-3-line',
      group: 'TOOLS' as const, order: 0, visible: true, appUrl: '/studio/', healthUrl: '', openMode: 'NEW_PAGE' as const,
    }
    vi.mocked(saveExternalAppRegistry).mockResolvedValue({
      version: 1,
      apps: [...DEFAULT_EXTERNAL_APPS, dataStudio],
    })
    const wrapper = await mountRegistrySettings()

    await wrapper.get('button.btn-primary.sm').trigger('click')
    await wrapper.get('input[placeholder="例如 data-studio"]').setValue(dataStudio.id)
    await wrapper.get('input[placeholder="例如 数据工作台"]').setValue(dataStudio.label)
    await wrapper.get('input[placeholder="ri-apps-2-line"]').setValue(dataStudio.icon)
    const selects = wrapper.findAll('.registry-editor select')
    await selects[0]!.setValue(dataStudio.group)
    await selects[1]!.setValue(dataStudio.openMode)
    await wrapper.get('input[type="number"]').setValue(dataStudio.order)
    await wrapper.get('input[placeholder="/my-app/ 或 https://app.example.com/"]').setValue(dataStudio.appUrl)
    await wrapper.get('.registry-editor').trigger('submit')
    await flushPromises()

    expect(saveExternalAppRegistry).toHaveBeenCalledWith([...DEFAULT_EXTERNAL_APPS, dataStudio])
    const nav = useNavStore()
    expect(nav.externalAppById('data-studio')?.label).toBe('数据工作台')
    expect(nav.groups.find((group) => group.label === '工具')?.items[0])
      .toBe(externalAppItemId('data-studio'))
  })

  it('在设置中统一维护左下角现有链接和新增图标', async () => {
    const wrapper = mount(SettingsView, { global: { plugins: [router] } })
    const entry = wrapper.findAll('.snav-item').find((button) => button.text() === '左下角链接')
    await entry!.trigger('click')
    await flushPromises()

    expect(fetchPreference).toHaveBeenCalledWith('quick-links')
    expect(useQuickLinks().links.value.length).toBeGreaterThan(0)
    const configuredLabels = wrapper.findAll('input[placeholder="名称（如 GitHub 仓库）"]')
      .map((input) => (input.element as HTMLInputElement).value)
    expect(configuredLabels).toContain('WAO 桌面宠物')
    expect(configuredLabels).toContain('Jenkins 部署中心')

    await wrapper.get('.ql-edit-actions .btn-ghost').trigger('click')
    const rows = wrapper.findAll('.ql-edit-row')
    const last = rows[rows.length - 1]!
    await last.get('input[placeholder="名称（如 GitHub 仓库）"]').setValue('内部工具')
    await last.get('input[placeholder="https://…"]').setValue('https://tools.example.com/')
    await wrapper.get('.ql-edit-actions .btn-primary').trigger('click')
    await flushPromises()

    expect(savePreference).toHaveBeenCalledOnce()
    const [key, raw] = vi.mocked(savePreference).mock.calls[0]!
    const stored = JSON.parse(raw) as { version: number; links: Array<{ label: string }> }
    expect(key).toBe('quick-links')
    expect(stored.version).toBe(2)
    expect(stored.links.some((link) => link.label === '内部工具')).toBe(true)
  })
})
