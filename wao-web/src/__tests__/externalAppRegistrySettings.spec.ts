import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { createPinia, setActivePinia } from 'pinia'
import SettingsView from '@/views/SettingsView.vue'
import {
  fetchBuiltinMenuRegistry,
  fetchExternalAppRegistry,
  saveBuiltinMenuRegistry,
  saveExternalAppRegistry,
} from '@/api/system'
import {
  DEFAULT_BUILTIN_MENUS,
  DEFAULT_EXTERNAL_APPS,
  type BuiltinMenuConfig,
  type BuiltinMenuRegistry,
  type ExternalAppConfig,
  type ExternalAppRegistry,
} from '@/config/nav'
import { fetchPreference, savePreference } from '@/api/favorites'

vi.mock('@/api/system', () => ({
  fetchExternalAppRegistry: vi.fn<() => Promise<ExternalAppRegistry>>(),
  saveExternalAppRegistry: vi.fn<(apps: ExternalAppConfig[]) => Promise<ExternalAppRegistry>>(),
  fetchBuiltinMenuRegistry: vi.fn<() => Promise<BuiltinMenuRegistry>>(),
  saveBuiltinMenuRegistry: vi.fn<(menus: BuiltinMenuConfig[]) => Promise<BuiltinMenuRegistry>>(),
  fetchNetworkOutageWindow: vi.fn<() => Promise<unknown>>(),
  saveNetworkOutageWindow: vi.fn<() => Promise<unknown>>(),
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
  ...(await importOriginal<typeof import('@/api/favorites')>()),
  fetchPreference: vi.fn<() => Promise<string>>(),
  savePreference: vi.fn<() => Promise<void>>(),
}))

async function mountSettings() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:p(.*)*', component: { template: '<div />' } }],
  })
  await router.push('/settings?tab=navigation')
  await router.isReady()
  const wrapper = mount(SettingsView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

function menuRow(wrapper: ReturnType<typeof mount>, label: string) {
  return wrapper
    .findAll('.menu-row')
    .find((row) => (row.find('input').element as HTMLInputElement | undefined)?.value === label)!
}

function menuGroup(wrapper: ReturnType<typeof mount>, label: string) {
  return wrapper
    .findAll('.menu-group')
    .find((group) => group.find('header').text().startsWith(label))!
}

describe('菜单与快捷链接统一设置', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(fetchBuiltinMenuRegistry).mockResolvedValue({
      version: 1,
      menus: DEFAULT_BUILTIN_MENUS,
    })
    vi.mocked(fetchExternalAppRegistry).mockResolvedValue({
      version: 1,
      apps: DEFAULT_EXTERNAL_APPS,
    })
    vi.mocked(saveBuiltinMenuRegistry).mockImplementation(async (menus) => ({ version: 1, menus }))
    vi.mocked(saveExternalAppRegistry).mockImplementation(async (apps) => ({ version: 1, apps }))
    vi.mocked(fetchPreference).mockResolvedValue('')
    vi.mocked(savePreference).mockResolvedValue(undefined)
  })

  it('把已注册应用并入真实菜单分组，并隐藏独立注册表编辑器', async () => {
    const wrapper = await mountSettings()

    expect(fetchBuiltinMenuRegistry).toHaveBeenCalledOnce()
    expect(fetchExternalAppRegistry).toHaveBeenCalledOnce()
    expect(
      wrapper.findAll('.menu-row input').map((input) => (input.element as HTMLInputElement).value),
    ).toEqual(expect.arrayContaining(['自媒体 Agent', '得到大脑', '资源管理']))
    expect(wrapper.get('.menu-card').text()).toContain('已注册应用')
    expect(wrapper.text()).not.toContain('注册新应用')
    expect(wrapper.find('.registry-editor').exists()).toBe(false)
  })

  it('分组下拉始终展示完整选项，隐藏后置底并由界面重建排序值', async () => {
    const wrapper = await mountSettings()
    const projects = menuRow(wrapper, '项目')
    const groupSelect = projects.get('select[aria-label="所属分组"]')
    expect(groupSelect.findAll('option').map((option) => option.text())).toEqual([
      '顶层',
      '公共',
      '个人',
      '工具',
    ])

    await groupSelect.setValue('TOOLS')
    await projects.get('button.visibility').trigger('click')
    await flushPromises()

    const toolsRows = menuGroup(wrapper, '工具').findAll('.menu-row')
    const projectIndex = toolsRows.findIndex(
      (row) => (row.find('input').element as HTMLInputElement).value === '项目',
    )
    const lastVisibleIndex = toolsRows.reduce(
      (lastIndex, row, index) =>
        row.get('button.visibility').classes('on') ? index : lastIndex,
      -1,
    )
    expect(projectIndex).toBeGreaterThan(lastVisibleIndex)
    await wrapper.get('.menu-card button.primary').trigger('click')
    await flushPromises()

    const saved = vi.mocked(saveBuiltinMenuRegistry).mock.calls[0]![0]
    const savedToolMenus = saved.filter((menu) => menu.group === 'TOOLS')
    const project = savedToolMenus.find((menu) => menu.id === 'projects')!
    expect(project).toMatchObject({ group: 'TOOLS', visible: false })
    expect(project.order).toBeGreaterThan(
      Math.max(...savedToolMenus.filter((menu) => menu.visible).map((menu) => menu.order)),
    )
    expect(new Set(savedToolMenus.map((menu) => menu.order)).size).toBe(savedToolMenus.length)
  })

  it('外部应用可拖进其他分组，快捷链接可配置 WAO 页签并保存 v2 偏好', async () => {
    const wrapper = await mountSettings()
    const external = menuRow(wrapper, '自媒体 Agent')
    const transfer = { setData: vi.fn<(format: string, data: string) => void>(), effectAllowed: '' }
    await external.get('.drag-handle').trigger('dragstart', { dataTransfer: transfer })
    await menuGroup(wrapper, '工具').trigger('drop')
    await wrapper.get('.menu-card button.primary').trigger('click')
    await flushPromises()

    expect(saveExternalAppRegistry).toHaveBeenCalledWith(
      expect.arrayContaining([
        expect.objectContaining({ id: 'media-agent', group: 'TOOLS' }),
        expect.objectContaining({ id: 'getnote', openMode: 'NEW_PAGE' }),
        expect.objectContaining({ id: 'jumpserver', openMode: 'NEW_PAGE' }),
      ]),
    )

    const firstLink = wrapper.get('.quick-link-row')
    await firstLink.get('select').setValue('TAB')
    await wrapper.get('.links-card button.primary').trigger('click')
    await flushPromises()

    const [key, raw] = vi.mocked(savePreference).mock.calls[0]!
    const stored = JSON.parse(raw) as { version: number; links: Array<{ openMode: string }> }
    expect(key).toBe('quick-links')
    expect(stored.version).toBe(2)
    expect(stored.links[0]?.openMode).toBe('TAB')
  })
})
