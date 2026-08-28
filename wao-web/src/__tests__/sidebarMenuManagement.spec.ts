import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import SideNav from '@/components/nav/SideNav.vue'
import { useNavStore } from '@/stores/nav'
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

vi.mock('@/api/system', () => ({
  fetchBuiltinMenuRegistry: vi.fn<() => Promise<BuiltinMenuRegistry>>(),
  fetchExternalAppRegistry: vi.fn<() => Promise<ExternalAppRegistry>>(),
  saveBuiltinMenuRegistry: vi.fn<(menus: BuiltinMenuConfig[]) => Promise<BuiltinMenuRegistry>>(),
  saveExternalAppRegistry: vi.fn<(apps: ExternalAppConfig[]) => Promise<ExternalAppRegistry>>(),
}))

vi.mock('@/api/favorites', () => ({
  fetchPreference: vi.fn<() => Promise<string>>().mockResolvedValue(''),
  savePreference: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
  fetchFavorites: vi.fn<() => Promise<unknown[]>>().mockResolvedValue([]),
  apiAddFavorite: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
  apiRemoveFavorite: vi.fn<() => Promise<void>>().mockResolvedValue(undefined),
}))

describe('桌面侧栏直接维护菜单', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    document.body.innerHTML = ''
    window.matchMedia = ((query: string) => ({
      matches: false,
      media: query,
      addEventListener: vi.fn<() => void>(),
      removeEventListener: vi.fn<() => void>(),
    })) as unknown as typeof window.matchMedia
    setActivePinia(createPinia())
    vi.mocked(fetchBuiltinMenuRegistry).mockResolvedValue({
      version: 27,
      menus: DEFAULT_BUILTIN_MENUS,
    })
    vi.mocked(fetchExternalAppRegistry).mockResolvedValue({
      version: 6,
      apps: DEFAULT_EXTERNAL_APPS,
    })
    vi.mocked(saveBuiltinMenuRegistry).mockImplementation(async (menus) => ({ version: 27, menus }))
    vi.mocked(saveExternalAppRegistry).mockImplementation(async (apps) => ({ version: 6, apps }))
  })

  async function mountSideNav() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' }, meta: { module: 'home' } },
        { path: '/projects', component: { template: '<div />' }, meta: { module: 'projects' } },
        { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(SideNav, {
      attachTo: document.body,
      global: { plugins: [router], stubs: { QuickLinks: true } },
    })
    await flushPromises()
    return wrapper
  }

  it('拖到底部后以原菜单图标显示，并可右键改名写回注册表', async () => {
    const wrapper = await mountSideNav()
    const transfer = { setData: vi.fn<(format: string, data: string) => void>(), effectAllowed: '' }
    await wrapper.get('[title="项目"]').trigger('dragstart', { dataTransfer: transfer })
    await wrapper.get('.quick-row').trigger('drop')
    await flushPromises()

    expect(saveBuiltinMenuRegistry).toHaveBeenCalledWith(
      expect.arrayContaining([expect.objectContaining({ id: 'projects', placement: 'DOCK' })]),
    )
    const docked = wrapper.get('.dock-item[title="项目"]')
    expect(wrapper.findAll('.items .item[title="项目"]')).toHaveLength(0)

    await docked.trigger('contextmenu', { clientX: 20, clientY: 30 })
    const input = document.querySelector<HTMLInputElement>('#side-menu-rename')!
    input.value = '我的项目'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    document
      .querySelector<HTMLFormElement>('.rename-menu')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    const saveCalls = vi.mocked(saveBuiltinMenuRegistry).mock.calls
    expect(saveCalls[saveCalls.length - 1]?.[0]).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ id: 'projects', label: '我的项目', placement: 'DOCK' }),
      ]),
    )
    expect(wrapper.get('.dock-item').attributes('title')).toBe('我的项目')
    wrapper.unmount()
  })

  it('跨组拖拽共同重建内置与外部菜单顺序，失败时恢复服务端权威配置', async () => {
    const nav = useNavStore()
    await nav.reloadMenuRegistries()
    await nav.moveMenu('projects', 'MENU', 'TOOLS')

    expect(nav.menuConfig('projects')).toMatchObject({ group: 'TOOLS', placement: 'MENU' })
    expect(saveBuiltinMenuRegistry).toHaveBeenCalled()
    expect(saveExternalAppRegistry).toHaveBeenCalled()

    vi.mocked(saveBuiltinMenuRegistry).mockRejectedValueOnce(new Error('网络不可用'))
    await expect(nav.moveMenu('projects', 'DOCK', 'TOOLS')).rejects.toThrow('网络不可用')
    expect(nav.menuConfig('projects')).toMatchObject({ group: 'PUBLIC', placement: 'MENU' })
    expect(nav.menuMutationError).toBe('网络不可用')
  })
})
