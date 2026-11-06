import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import TodoView from '@/views/TodoView.vue'
import { useSessionStore } from '@/stores/session'
import { seedDataStore } from './helpers/seedDataStore'

vi.mock('@/api/taskLabels', () => ({ fetchTaskLabels: vi.fn(async () => []), saveTaskLabels: vi.fn() }))
vi.mock('@/api/favorites', async original => ({ ...await original<typeof import('@/api/favorites')>(), fetchPreference: vi.fn(async () => ''), savePreference: vi.fn(async () => {}) }))
vi.mock('@/api/smartListVisibility', () => ({ fetchSmartListVisibility: vi.fn(async () => ({})), saveSmartListVisibility: vi.fn(async () => {}) }))
enableAutoUnmount(afterEach)
beforeEach(() => {
  localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia())
  useSessionStore().accept({ accountId: 'route-test', member: { id: 'wg', name: '成员', initial: '测', role: '', color: '' }, identities: [], createdAt: '' })
  const data = seedDataStore(); data.taskLoadState = 'loaded'; data.apiLive = false
})
const stubs = {
  ProjectChecklistView: { template: '<section><slot name="list-header" /></section>' },
  TodoDayView: { props: ['date'], template: '<section aria-label="每日回顾">{{date}}</section>' },
  TaskQuadrantView: true, TodoViewControls: true, TodayDates: true,
}
function clickNavigation(wrapper: VueWrapper, name: string) {
  return wrapper.findAll('.task-navigation button').find(button => button.text().trim() === name)!.trigger('click')
}
async function setup() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/todo', component: TodoView }] })
  await router.push('/todo?date=2026-11-06'); await router.isReady()
  const render = () => mount(TodoView, { global: { plugins: [router], stubs } })
  return { router, render, wrapper: render() }
}

it.each(['所有', '今天', '清单'])('从日期回顾切换到%s后，刷新和前进后退保留目的地', async name => {
  const { router, render, wrapper } = await setup()
  await flushPromises()
  expect(wrapper.get('[aria-label="每日回顾"]').text()).toBe('2026-11-06')
  // 清单使用当前种子名称，避免测试依赖文案中的示例清单。
  const target = name === '清单' ? wrapper.get('.list-entry>button:first-child').text().trim() : name
  await clickNavigation(wrapper, target); await flushPromises()
  expect(router.currentRoute.value.query.date).toBeUndefined()
  expect(wrapper.get('h1').text()).toBe(target)
  const destination = router.currentRoute.value.fullPath
  expect(destination).toMatch(name === '清单' ? /list=/ : /scope=/)

  wrapper.unmount()
  // 清空内存模拟在同一链接新开页面，选择必须由 URL 恢复。
  sessionStorage.clear()
  const reloaded = render(); await flushPromises()
  expect(reloaded.get('h1').text()).toBe(target)
  await router.push('/todo?date=2026-11-05'); await flushPromises()
  expect(reloaded.get('[aria-label="每日回顾"]').text()).toBe('2026-11-05')
  router.back(); await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe(destination)
  expect(reloaded.get('h1').text()).toBe(target)
  router.forward(); await flushPromises()
  expect(reloaded.get('[aria-label="每日回顾"]').text()).toBe('2026-11-05')
  router.back(); await flushPromises()
  expect(reloaded.get('h1').text()).toBe(target)
})
