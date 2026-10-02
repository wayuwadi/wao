import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import type { AuthSession } from '@/api/auth'
vi.mock('@/layouts/AppLayout.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/views/HomeView.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/views/TodoView.vue', () => ({ default: { template: '<div />' } }))
import router from '@/router'
beforeEach(() => {
  setActivePinia(createPinia())
  useSessionStore().initialized = true
})
describe('公共入口路由', () => {
  it('访客受保护深链接返回保留 query/hash 的登录意图，主页进入静默', async () => {
    await router.push('/todo?task=task-1#detail')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/todo?task=task-1#detail')
    await router.push('/')
    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBeUndefined()
  })
  it('已登录可以访问新旧休息入口，不强制跳回工作区', async () => {
    useSessionStore().account = { member: { id: 'member' } } as AuthSession
    for (const path of ['/breathe', '/login']) {
      await router.push('/todo')
      await router.push(path)
      expect(router.currentRoute.value.path).toBe(path)
      expect(router.currentRoute.value.meta.public).toBe(true)
    }
    await router.push('/?run=run-1&console=1#details')
    expect(router.currentRoute.value.fullPath).toBe('/?run=run-1&console=1#details')
  })
  it('服务暂不可用不封锁休息入口，受保护页面仍进入恢复页', async () => {
    useSessionStore().connectionError = 'unavailable'
    await router.push('/breathe')
    expect(router.currentRoute.value.path).toBe('/breathe')
    await router.push('/todo')
    expect(router.currentRoute.value.name).toBe('connection-recovery')
  })
})
