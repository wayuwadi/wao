import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useFavoritesStore } from '@/stores/favorites'
import { useKnowledgeFavoritesStore } from '@/stores/knowledgeFavorites'
import { seedDataStore } from './helpers/seedDataStore'
import SideNav from '@/components/nav/SideNav.vue'
import { FIXED_NAV_IDS, MODULES } from '@/config/nav'
import { useSessionStore } from '@/stores/session'
import type { AuthSession } from '@/api/auth'
vi.mock('@/components/WaoLogo.vue', () => ({ default: { template: '<span />' } }))
vi.mock('@/api/system', () => ({ fetchExternalAppRegistry: vi.fn().mockResolvedValue({ apps: [] }) }))
const labels = ['首页', '小记', '待办', '项目', '知识库', '草图', '密码本', '日历', '番茄钟', '倒数日', '习惯', '全部']
describe('统一主菜单', () => {
  beforeEach(() => {
    localStorage.clear(); setActivePinia(createPinia())
    window.matchMedia = vi.fn().mockReturnValue({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() })
  })
  it('收藏显示在固定菜单下，支持跳转和即时取消收藏', async () => {
    const data = seedDataStore()
    const favorites = useFavoritesStore()
    const knowledge = useKnowledgeFavoritesStore()
    vi.spyOn(favorites, 'load').mockResolvedValue()
    vi.spyOn(knowledge, 'load').mockResolvedValue()
    const project = data.projects[0]!
    const kb = data.kbs.find(item => !item.notes)!
    favorites.items = [{ type: 'project', targetId: project.id, label: project.name, parentId: null }]
    knowledge.ids = [kb.id, 'missing-kb']
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
    await router.push('/')
    const w = mount(SideNav, { global: { plugins: [router] } })
    expect(w.findAll('.favorite-link').map(item => item.text())).toEqual([project.name, kb.name])
    await w.findAll('.favorite-link')[1]!.trigger('click'); await flushPromises()
    expect(router.currentRoute.value.path).toBe(`/kb/${kb.id}`)
    expect(w.findAll('.favorite-link')[1]!.attributes('aria-current')).toBe('page')
    expect(w.get('[title="知识库"]').classes()).not.toContain('active')
    favorites.items = []; knowledge.ids = []; await flushPromises()
    expect(w.findAll('.favorite-link')).toHaveLength(0)
    expect(w.findAll('.item').map(item => item.text())).toEqual(labels)
    w.unmount()
  })
  it('旧个人偏好和账号切换不影响菜单顺序，也不提供右键或拖拽编辑', async () => {
    localStorage.setItem('wao-nav-v1', JSON.stringify({ collapsed: false, personalOrder: ['vault'], foldedGroups: { ROOT: true } }))
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
    await router.push('/')
    const w = mount(SideNav, { global: { plugins: [router] } })
    expect(w.findAll('.item').map(x => x.text())).toEqual(labels)
    await w.get('[title="项目"]').trigger('contextmenu')
    expect(w.find('.menu-actions').exists()).toBe(false)
    expect(w.find('[draggable="true"]').exists()).toBe(false)
    for (const id of FIXED_NAV_IDS) {
      await w.get(`[title="${MODULES[id].label}"]`).trigger('click'); await flushPromises()
      expect(router.currentRoute.value.path).toBe(MODULES[id].path)
    }
    useSessionStore().account = { member: { id: 'another-member' } } as AuthSession
    await flushPromises()
    expect(w.findAll('.item').map(x => x.text())).toEqual(labels)
    await w.get('.user').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.path).toBe('/settings')
    w.unmount()
  })
})
