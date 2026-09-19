import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import * as auth from '@/api/auth'
import ConnectionRecoveryView from '@/views/ConnectionRecoveryView.vue'
vi.mock('@/api/auth', () => ({ getSession: vi.fn(), loginWithEmail: vi.fn(), logoutSession: vi.fn(), registerWithEmail: vi.fn(), claimMember: vi.fn() }))
vi.mock('@/api/board', () => ({ fetchMembers: vi.fn().mockResolvedValue([]), fetchOrg: vi.fn().mockResolvedValue({}) }))
vi.mock('@/api/desktopCache', () => ({ setDesktopCacheMember: vi.fn() }))
const account = { member: { id: 'u1', name: '用户' } } as auth.AuthSession
beforeEach(() => { setActivePinia(createPinia()); vi.mocked(auth.getSession).mockReset() })
it('网络错误保留未确认状态，可重试同一会话', async () => {
  vi.mocked(auth.getSession).mockRejectedValueOnce(new Error('网络中断')).mockResolvedValueOnce(account)
  const store = useSessionStore()
  await store.initialize()
  expect(store.initialized).toBe(false)
  expect(store.connectionError).toBe('网络中断')
  await store.initialize()
  expect(store.connectionError).toBe('')
  expect(store.authed).toBe(true)
  expect(store.initialized).toBe(true)
})
it('只有明确401认定未登录，后续不反复请求', async () => {
  vi.mocked(auth.getSession).mockRejectedValue(Object.assign(new Error('未登录'), { status: 401 }))
  const store = useSessionStore()
  await store.initialize()
  await store.initialize()
  expect(store.initialized).toBe(true)
  expect(store.connectionError).toBe('')
  expect(store.authed).toBe(false)
  expect(auth.getSession).toHaveBeenCalledOnce()
})
it('并发初始化共享请求，403不能当成未登录', async () => {
  vi.mocked(auth.getSession).mockRejectedValue({ response: { status: 403 } })
  const store = useSessionStore()
  await Promise.all([store.initialize(), store.initialize()])
  expect(auth.getSession).toHaveBeenCalledOnce()
  expect(store.initialized).toBe(false)
  expect(store.connectionError).not.toBe('')
})
it('恢复页重试后回原地址并保留查询参数', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/connection-recovery', component: ConnectionRecoveryView },
    { path: '/todo', component: { template: '<div>待办</div>' } },
  ] })
  await router.push('/connection-recovery?redirect=%2Ftodo%3Ffilter%3Dmine')
  const store = useSessionStore()
  store.connectionError = '断网'
  vi.mocked(auth.getSession).mockResolvedValue(account)
  const wrapper = mount(ConnectionRecoveryView, { global: { plugins: [router] } })
  await wrapper.get('button').trigger('click')
  await flushPromises()
  expect(router.currentRoute.value.fullPath).toBe('/todo?filter=mine')
  wrapper.unmount()
})
