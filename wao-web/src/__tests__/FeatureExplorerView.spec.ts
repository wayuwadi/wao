import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it } from 'vitest'
import FeatureExplorerView from '@/views/FeatureExplorerView.vue'
import { normalizeBuiltinMenu } from '@/config/nav'
import { useSessionStore } from '@/stores/session'

async function render(hash = '') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/features', component: FeatureExplorerView },
    { path: '/navigation', component: { template: '<div>目录</div>' } },
  ] })
  await router.push('/features' + hash)
  const wrapper = mount(FeatureExplorerView, { global: { plugins: [router], stubs: { ReleaseHistory: { template: '<div>历史更新</div>' }, ReleaseEditionContent: true } } })
  await flushPromises()
  return { router, wrapper }
}
beforeEach(() => {
  sessionStorage.clear(); setActivePinia(createPinia())
  useSessionStore().accept({ accountId: 'a', member: { id: 'a', name: 'a', initial: 'a', color: '', role: '' }, identities: [], createdAt: '' })
})
describe('版本更新与唯一功能目录', () => {
  it('默认展示当前更新，目录入口直接导航且没有重复目录', async () => {
    const { router, wrapper } = await render()
    expect(wrapper.get('#current-update').isVisible()).toBe(true)
    expect(wrapper.find('#all-features').exists()).toBe(false)
    await wrapper.get('.directory-link').trigger('click'); await flushPromises()
    expect(router.currentRoute.value.path).toBe('/navigation'); wrapper.unmount()
  })
  it('旧功能目录书签替换跳转，历史与当前更新深链继续有效', async () => {
    const legacy = await render('#all-features')
    expect(legacy.router.currentRoute.value.path).toBe('/navigation'); legacy.wrapper.unmount()
    const history = await render('#recent-updates')
    expect(history.wrapper.get('#recent-updates').isVisible()).toBe(true)
    await history.wrapper.get('#explorer-tab-recent-updates').trigger('keydown', { key: 'ArrowRight' }); await flushPromises()
    expect(history.router.currentRoute.value.hash).toBe('#current-update'); history.wrapper.unmount()
  })
  it('重建后保留更新页选项，明确深链优先于历史选择', async () => {
    const first = await render('#recent-updates'); first.wrapper.unmount()
    const restored = await render(); expect(restored.wrapper.get('#recent-updates').isVisible()).toBe(true); restored.wrapper.unmount()
    const current = await render('#current-update'); expect(current.wrapper.get('#current-update').isVisible()).toBe(true); current.wrapper.unmount()
  })
  it('迁移旧默认名称并保留成员自定义名称', () => {
    const menu = { id: 'changelog' as const, label: '功能探索', icon: '', group: 'ROOT', order: 1, visible: true, openMode: 'TAB' as const }
    expect(normalizeBuiltinMenu(menu).label).toBe('版本更新')
    expect(normalizeBuiltinMenu({ ...menu, label: '我的更新' }).label).toBe('我的更新')
  })
})
