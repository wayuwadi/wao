import { expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import NotFoundView from '@/views/NotFoundView.vue'
import router from '@/router'
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ initialize: vi.fn(), authed: false }) }))
it('无效地址匹配独立页面，不影响已知分享路由或首页', () => {
  expect(router.resolve('/unknown-page?secret=do-not-display').name).toBe('not-found')
  expect(router.resolve('/f/file-id').name).toBe('file-download')
  expect(router.resolve('/s/t/task-id').name).toBe('share-task')
  expect(router.resolve('/').name).toBe('home')
})
it('无历史时提供明确恢复入口且不回显地址参数', async () => {
  const local = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/:pathMatch(.*)*', component: NotFoundView },
    { path: '/navigation', component: { template: '<div>全部功能</div>' } },
    { path: '/', component: { template: '<div>首页</div>' } },
  ] })
  await local.push('/unknown?secret=do-not-display')
  const wrapper = mount(NotFoundView, { global: { plugins: [local] } })
  expect(wrapper.text()).toContain('找不到这个页面')
  expect(wrapper.text()).not.toContain('do-not-display')
  expect(wrapper.find('button').exists()).toBe(false)
  await wrapper.get('a[href="/navigation"]').trigger('click')
  await flushPromises()
  expect(local.currentRoute.value.path).toBe('/navigation')
  wrapper.unmount()
})
