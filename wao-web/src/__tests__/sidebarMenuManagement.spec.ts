import { useTopShortcutsStore, favoriteShortcut } from '@/stores/topShortcuts'
import { useDataStore } from '@/stores/data'
import { useKnowledgeFavoritesStore } from '@/stores/knowledgeFavorites'
import { seedDataStore } from './helpers/seedDataStore'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import TopMenuBar from '@/components/nav/TopMenuBar.vue'
import SideNav from '@/components/nav/SideNav.vue'
import { useFavoritesStore } from '@/stores/favorites'
import { fetchNavigationLayout, saveNavigationLayout } from '@/api/navigationLayout'
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

// 导航测试不加载品牌图片，避免 Windows 公共资源路径的 Vitest 转换限制。
vi.mock('@/components/WaoLogo.vue', () => ({ default: { template: '<span />' } }))

vi.mock('@/api/navigationLayout', () => ({
  fetchNavigationLayout: vi.fn(),
  saveNavigationLayout: vi.fn(),
}))

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
    vi.mocked(fetchNavigationLayout).mockResolvedValue({ revision: 'none', layout: null })
    vi.mocked(saveNavigationLayout).mockImplementation(async (_revision, layout) => ({
      revision: crypto.randomUUID(),
      layout,
    }))
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

  async function mountSideNav(withTop = false) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' }, meta: { module: 'home' } },
        { path: '/kb/:id', name: 'kb', component: { template: '<div />' }, meta: { module: 'knowledge' } },
        { path: '/projects', component: { template: '<div />' }, meta: { module: 'projects' } },
        { path: '/projects/:id', component: { template: '<div />' }, meta: { module: 'projects' } },
        { path: '/:pathMatch(.*)*', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(
      withTop
        ? { components: { SideNav, TopMenuBar }, template: '<SideNav /><TopMenuBar />' }
        : SideNav,
      {
        attachTo: document.body,
        global: { plugins: [router], stubs: { QuickLinks: true } },
      },
    )
    await flushPromises()
    return wrapper
  }

  it('任务文档收藏统一显示顶部，与已固定入口去重且可以取消', async () => {
    const wrapper = await mountSideNav(true)
    const favorites = useFavoritesStore(), shortcuts = useTopShortcutsStore()
    favorites.items = [
      { type: 'doc', targetId: 'd1', label: '收藏文档', parentId: 'kb1' },
      { type: 'task', targetId: 't1', label: '收藏任务', parentId: 'p1' },
    ]
    shortcuts.loaded = true; shortcuts.state = 'loaded'
    shortcuts.items = [favoriteShortcut(favorites.items[0]!)!]
    await flushPromises()
    expect(wrapper.findAll('.fav-item')).toHaveLength(0)
    expect(wrapper.findAll('.personal-shortcut')).toHaveLength(2)
    await wrapper.get('[aria-label="移除顶部快捷入口：收藏文档"]').trigger('click')
    await flushPromises()
    expect(favorites.items.map(item => item.targetId)).toEqual(['t1'])
    expect(shortcuts.items).toHaveLength(0)
    expect(wrapper.findAll('.personal-shortcut')).toHaveLength(1)
    wrapper.unmount()
  })

  it('知识库只高亮收藏子项，折叠后恢复父菜单高亮', async () => {
    seedDataStore(useDataStore())
    const wrapper = await mountSideNav()
    const kb = useDataStore().kbs[0]!
    useKnowledgeFavoritesStore().ids = [kb.id]
    await flushPromises()
    await wrapper.get('.knowledge-children .item').trigger('click')
    await flushPromises()
    expect(wrapper.get('.knowledge-children .item').classes()).toContain('active')
    expect(wrapper.get('[aria-label="知识库"]').classes()).not.toContain('active')
    useNavStore().toggleCollapse(); await flushPromises()
    expect(wrapper.get('[aria-label="知识库"]').classes()).toContain('active')
    wrapper.unmount()
  })

  it('项目收藏位于项目菜单下，取消收藏不跳转且保留其他收藏', async () => {
    const wrapper = await mountSideNav()
    const favorites = useFavoritesStore()
    favorites.items = [
      { type: 'project', targetId: 'p1', label: '收藏项目', parentId: null },
      { type: 'doc', targetId: 'd1', label: '收藏文档', parentId: 'kb1' },
    ]
    await flushPromises()
    const children = wrapper.get('.project-children')
    expect(children.element.previousElementSibling?.getAttribute('aria-label')).toBe('项目')
    expect(children.get('.item').find('i').exists()).toBe(false)
    expect(wrapper.findAll('.fav-item').map((item) => item.text())).toEqual([])
    await children.get('.item').trigger('click')
    await flushPromises()
    expect(children.get('.item').classes()).toContain('active')
    expect(wrapper.get('[aria-label="项目"]').classes()).not.toContain('active')
    useNavStore().toggleCollapse()
    await flushPromises()
    expect(wrapper.get('[aria-label="项目"]').classes()).toContain('active')
    useNavStore().toggleCollapse()
    await flushPromises()
    await wrapper.get('[aria-label="取消收藏：收藏项目"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('.project-children').exists()).toBe(false)
    expect(favorites.items.map((item) => item.label)).toEqual(['收藏文档'])
    wrapper.unmount()
  })

  it('仅收藏项目时不显示顶部收藏组，收缩侧栏隐藏项目子项', async () => {
    const wrapper = await mountSideNav()
    useFavoritesStore().items = [{ type: 'project', targetId: 'p1', label: '收藏项目', parentId: null }]
    await flushPromises()
    expect(wrapper.find('.fav-group').exists()).toBe(false)
    expect(wrapper.find('.project-children').exists()).toBe(true)
    useNavStore().toggleCollapse()
    await flushPromises()
    expect(wrapper.find('.project-children').exists()).toBe(false)
    useNavStore().toggleCollapse()
    await flushPromises()
    expect(wrapper.get('.project-children').text()).toContain('收藏项目')
    wrapper.unmount()
  })

  it('拖到底部后以原菜单图标显示，并可右键改名保存个人布局', async () => {
    const wrapper = await mountSideNav()
    const transfer = { setData: vi.fn<(format: string, data: string) => void>(), effectAllowed: '' }
    await wrapper.get('[title="项目"]').trigger('dragstart', { dataTransfer: transfer })
    await wrapper.get('.quick-row').trigger('drop')
    await flushPromises()

    expect(saveNavigationLayout).toHaveBeenCalledWith(
      'none',
      expect.objectContaining({
        items: expect.arrayContaining([
          expect.objectContaining({ id: 'projects', placement: 'DOCK' }),
        ]),
      }),
    )
    const docked = wrapper.get('.dock-item[title="项目"]')
    expect(wrapper.findAll('.items .item[title="项目"]')).toHaveLength(0)

    await docked.trigger('contextmenu', { clientX: 20, clientY: 30 })
    document.querySelector<HTMLButtonElement>('.menu-actions button')!.click()
    await flushPromises()
    const input = document.querySelector<HTMLInputElement>('#side-menu-rename')!
    input.value = '我的项目'
    input.dispatchEvent(new Event('input', { bubbles: true }))
    document
      .querySelector<HTMLFormElement>('.rename-menu')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()

    const saveCalls = vi.mocked(saveNavigationLayout).mock.calls
    expect(saveCalls[saveCalls.length - 1]?.[1].items).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ id: 'projects', label: '我的项目', placement: 'DOCK' }),
      ]),
    )
    expect(wrapper.get('.dock-item').attributes('title')).toBe('我的项目')
    wrapper.unmount()
  })

  it('侧栏拖到顶部形成可点击按钮，并可排序、移到底部和拖回侧栏', async () => {
    const wrapper = await mountSideNav(true)
    const nav = useNavStore()
    const transfer = { setData: vi.fn(), effectAllowed: '' }
    await wrapper.get('.items [title="项目"]').trigger('dragstart', { dataTransfer: transfer })
    await wrapper.get('.top-menu-bar').trigger('drop')
    await flushPromises()
    expect(nav.topItems).toEqual(['projects'])
    expect(wrapper.find('.items [title="项目"]').exists()).toBe(false)
    await wrapper.get('.top-menu-item').trigger('click')
    await flushPromises()
    expect(wrapper.get('.top-menu-item').attributes('aria-current')).toBe('page')

    await wrapper.get('.items [title="日历"]').trigger('dragstart', { dataTransfer: transfer })
    await wrapper.get('.top-menu-item').trigger('drop')
    await flushPromises()
    expect(nav.topItems).toEqual(['calendar', 'projects'])
    await wrapper
      .get('.top-menu-item[title="项目"]')
      .trigger('dragstart', { dataTransfer: transfer })
    await wrapper.get('.top-menu-item[title="日历"]').trigger('drop')
    await flushPromises()
    expect(nav.topItems).toEqual(['projects', 'calendar'])

    await wrapper
      .get('.top-menu-item[title="项目"]')
      .trigger('dragstart', { dataTransfer: transfer })
    await wrapper.get('.quick-row').trigger('drop')
    await flushPromises()
    expect(nav.dockItems).toEqual(['projects'])
    await wrapper.get('.dock-item[title="项目"]').trigger('dragstart', { dataTransfer: transfer })
    await wrapper.get('.top-menu-bar').trigger('drop')
    await flushPromises()
    expect(nav.topItems).toEqual(['calendar', 'projects'])
    await wrapper
      .get('.top-menu-item[title="项目"]')
      .trigger('dragstart', { dataTransfer: transfer })
    await wrapper.get('.items [title="首页"]').trigger('drop')
    await flushPromises()
    expect(nav.menuConfig('projects')).toMatchObject({ placement: 'MENU', group: 'ROOT' })
    expect(wrapper.find('.items [title="项目"]').exists()).toBe(true)
    wrapper.unmount()
  })

  it('顶部外部应用沿用新窗口打开方式，失败恢复位置且可重新拖拽', async () => {
    const wrapper = await mountSideNav(true)
    const nav = useNavStore()
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    await nav.moveMenu('external:getnote', 'TOP', 'TOOLS')
    await flushPromises()
    await wrapper.get('.top-menu-item').trigger('click')
    expect(open).toHaveBeenCalledWith('https://www.biji.com/', '_blank', 'noopener,noreferrer')
    vi.mocked(saveNavigationLayout).mockRejectedValueOnce(new Error('网络不可用'))
    await wrapper.get('.items [title="项目"]').trigger('dragstart')
    await wrapper.get('.top-menu-bar').trigger('drop')
    await flushPromises()
    expect(
      wrapper
        .findAll('[role="alert"]')
        .map((el) => el.text())
        .join(' '),
    ).toContain('网络不可用')
    expect(wrapper.find('.items [title="项目"]').exists()).toBe(true)
    await wrapper.get('.items [title="项目"]').trigger('dragstart')
    await wrapper.get('.top-menu-bar').trigger('drop')
    await flushPromises()
    expect(nav.topItems).toContain('projects')
    open.mockRestore()
    wrapper.unmount()
  })

  it('无菜单拖拽时忽略普通文本落入，保存中不允许另一次拖拽', async () => {
    const wrapper = await mountSideNav(true)
    await wrapper.get('.top-menu-bar').trigger('drop')
    expect(saveNavigationLayout).not.toHaveBeenCalled()
    useNavStore().menuMutationState = 'saving'
    await wrapper.get('.items [title="项目"]').trigger('dragstart')
    await wrapper.get('.top-menu-bar').trigger('drop')
    expect(saveNavigationLayout).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('跨组拖拽共同重建内置与外部菜单顺序，失败时恢复服务端权威配置', async () => {
    const nav = useNavStore()
    await nav.reloadMenuRegistries()
    await nav.moveMenu('projects', 'MENU', 'TOOLS')

    expect(nav.menuConfig('projects')).toMatchObject({ group: 'TOOLS', placement: 'MENU' })
    expect(saveNavigationLayout).toHaveBeenCalledTimes(1)
    expect(saveBuiltinMenuRegistry).not.toHaveBeenCalled()
    expect(saveExternalAppRegistry).not.toHaveBeenCalled()

    vi.mocked(saveNavigationLayout).mockRejectedValueOnce(new Error('网络不可用'))
    await expect(nav.moveMenu('projects', 'DOCK', 'TOOLS')).rejects.toThrow('网络不可用')
    expect(nav.menuConfig('projects')).toMatchObject({ group: 'TOOLS', placement: 'MENU' })
    expect(nav.menuMutationError).toBe('网络不可用')
  })

  it('右键菜单提供置顶与隐藏并保存个人布局', async () => {
    const wrapper = await mountSideNav()
    await wrapper.get('[title="日历"]').trigger('contextmenu', { clientX: 40, clientY: 80 })
    const actions = [...document.querySelectorAll<HTMLButtonElement>('.menu-actions button')]
    expect(actions.map((button) => button.textContent?.trim())).toEqual(['重命名', '置顶', '隐藏'])
    actions[1]!.click()
    await flushPromises()

    const pinCalls = vi.mocked(saveNavigationLayout).mock.calls
    const pinCall = pinCalls[pinCalls.length - 1]?.[1].items ?? []
    const calendar = pinCall.find((menu) => menu.id === 'calendar')!
    const personalPeers = pinCall.filter((menu) => menu.visible && menu.group === 'PERSONAL')
    expect(calendar.order).toBe(Math.min(...personalPeers.map((menu) => menu.order)))

    await wrapper.get('[title="日历"]').trigger('contextmenu', { clientX: 40, clientY: 80 })
    document.querySelectorAll<HTMLButtonElement>('.menu-actions button')[2]!.click()
    await flushPromises()

    const hideCalls = vi.mocked(saveNavigationLayout).mock.calls
    expect(hideCalls[hideCalls.length - 1]?.[1].items).toEqual(
      expect.arrayContaining([expect.objectContaining({ id: 'calendar', visible: false })]),
    )
    expect(wrapper.find('[title="日历"]').exists()).toBe(false)
    wrapper.unmount()
  })
})
