import { useSessionStore } from '@/stores/session'
import { createPinia, setActivePinia } from 'pinia'
vi.mock('ant-design-vue', () => ({
  Modal: { props: ['open'], template: '<div v-if="open" role="dialog"><slot /></div>' },
}))
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import FeatureExplorerView from '@/views/FeatureExplorerView.vue'
import { FEATURE_CATALOG } from '@/data/featureCatalog'
const Modal = { props: ['open'], template: '<div v-if="open" role="dialog"><slot /></div>' }
async function render(hash = '') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/features', component: FeatureExplorerView },
      { path: '/notes', component: { template: '<div>notes</div>' } },
    ],
  })
  await router.push('/features' + hash)
  const wrapper = mount(FeatureExplorerView, {
    global: { plugins: [router], stubs: { GitCommitHistory: true, Modal } },
  })
  return { router, wrapper }
}
describe('功能探索', () => {
  it('目录独立分页，切换选项卡保留页码，分类变更回到首页', async () => {
    const { wrapper } = await render()
    expect(wrapper.findAll('.catalog-card')).toHaveLength(6)
    await wrapper.get('#all-features [aria-label="下一页"]').trigger('click')
    const title = wrapper.get('.catalog-card h3').text()
    await wrapper.get('#explorer-tab-recent-updates').trigger('click')
    await flushPromises()
    expect(wrapper.get('#all-features').isVisible()).toBe(false)
    expect(wrapper.findAll('.release-edition')).toHaveLength(3)
    await wrapper.get('#explorer-tab-all-features').trigger('click')
    await flushPromises()
    expect(wrapper.get('.catalog-card h3').text()).toBe(title)
    await wrapper
      .findAll('.filter-bar button')
      .find((item) => item.text() === '计划与行动')!
      .trigger('click')
    expect(wrapper.findAll('.catalog-card')).toHaveLength(
      FEATURE_CATALOG.filter((item) => item.category === 'planning').length,
    )
    expect(wrapper.get('#all-features [aria-label="上一页"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
  it('旧更新深链、当前版本深链与键盘切换选中正确内容', async () => {
    const { router, wrapper } = await render('#recent-updates')
    expect(wrapper.get('#explorer-tab-recent-updates').attributes('aria-selected')).toBe('true')
    await wrapper.get('#explorer-tab-recent-updates').trigger('keydown', { key: 'ArrowRight' })
    await flushPromises()
    expect(router.currentRoute.value.hash).toBe('#current-update')
    expect(wrapper.get('#current-update').isVisible()).toBe(true)
    await router.replace('/features#all-features')
    await flushPromises()
    expect(wrapper.get('#all-features').isVisible()).toBe(true)
    wrapper.unmount()
  })
  it('功能先预览，主动进入真实路由', async () => {
    const { router, wrapper } = await render()
    await wrapper.get('[aria-label="了解小记"]').trigger('click')
    expect(router.currentRoute.value.path).toBe('/features')
    expect(wrapper.get('[role="dialog"]').text()).toContain('草稿、保存和失败重试')
    await wrapper.get('.detail-actions .primary').trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/notes')
    wrapper.unmount()
  })
  it('窄屏每页三项，跨断点保留原首条所在页', async () => {
    const old = window.innerWidth
    const { wrapper } = await render()
    await wrapper.get('#all-features [aria-label="下一页"]').trigger('click')
    const title = wrapper.get('.catalog-card h3').text()
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 })
    window.dispatchEvent(new Event('resize'))
    await flushPromises()
    expect(wrapper.findAll('.catalog-card')).toHaveLength(3)
    expect(wrapper.get('.catalog-card h3').text()).toBe(title)
    wrapper.unmount()
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: old })
  })
})

beforeEach(() => { sessionStorage.clear(); setActivePinia(createPinia()) })

it('卸载后重建恢复分类与页码，明确深链覆盖记住的 Tab', async () => {
  useSessionStore().accept({ accountId: 'a', member: { id: 'a', name: 'a', initial: 'a', color: '', role: '' }, identities: [], createdAt: '' })
  const first = await render()
  await first.wrapper.get('#all-features [aria-label="下一页"]').trigger('click')
  const title = first.wrapper.get('.catalog-card h3').text()
  await first.wrapper.get('#explorer-tab-recent-updates').trigger('click'); await flushPromises()
  first.wrapper.unmount()
  const restored = await render()
  expect(restored.wrapper.get('#explorer-tab-recent-updates').attributes('aria-selected')).toBe('true')
  restored.wrapper.unmount()
  const linked = await render('#all-features')
  expect(linked.wrapper.get('#explorer-tab-all-features').attributes('aria-selected')).toBe('true')
  expect(linked.wrapper.get('.catalog-card h3').text()).toBe(title)
  linked.wrapper.unmount()
})
