import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import SideNav from '@/components/nav/SideNav.vue'
import { FIXED_NAV_IDS, MODULES } from '@/config/nav'
import { useSessionStore } from '@/stores/session'
import type { AuthSession } from '@/api/auth'
vi.mock('@/components/WaoLogo.vue', () => ({ default: { template: '<span />' } }))
vi.mock('@/api/system', () => ({ fetchExternalAppRegistry: vi.fn().mockResolvedValue({ apps: [] }) }))
const labels = ['首页', '小记', '待办', '项目', '知识库', '草图', '密码本', '日历', '番茄钟', '倒数日', '习惯打卡', '全部功能']
describe('统一主菜单', () => {
  beforeEach(() => {
    localStorage.clear(); setActivePinia(createPinia())
    window.matchMedia = vi.fn().mockReturnValue({ matches: false, addEventListener: vi.fn(), removeEventListener: vi.fn() })
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
