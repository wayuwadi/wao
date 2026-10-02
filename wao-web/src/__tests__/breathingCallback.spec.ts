import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import FeishuCallbackView from '@/views/FeishuCallbackView.vue'
import { saveAuthHandoff, consumeAuthPractice, authReturnTarget } from '@/composables/breathingAuth'
import { exchangeFeishuCode } from '@/api/feishu'
import { useSessionStore } from '@/stores/session'
import type { AuthResolution } from '@/api/auth'
vi.mock('@/api/feishu', () => ({ exchangeFeishuCode: vi.fn<typeof exchangeFeishuCode>() }))
let wrapper: ReturnType<typeof mount>
beforeEach(() => {
  sessionStorage.clear()
  consumeAuthPractice()
  vi.clearAllMocks()
})
afterEach(() => {
  wrapper?.unmount()
})
async function setup(target: string, claim = false) {
  saveAuthHandoff(
    target,
    { mode: 'breathing', total: 60000, remaining: 41000, inhale: 4, exhale: 6 },
    'https://auth.example/?state=matching',
  )
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/auth/feishu/callback', component: FeishuCallbackView },
      ...['/breathe', '/todo', '/auth/claim', '/'].map((path) => ({
        path,
        component: { template: '<div />' },
      })),
    ],
  })
  await router.push('/auth/feishu/callback?code=once&state=matching')
  const pinia = createPinia(),
    session = useSessionStore(pinia)
  vi.mocked(exchangeFeishuCode).mockResolvedValue({
    claimRequired: claim,
    session: claim ? undefined : { member: { id: 'm' } },
  } as AuthResolution)
  wrapper = mount(FeishuCallbackView, { global: { plugins: [pinia, router] } })
  await flushPromises()
  return { router, session }
}
describe('飞书回调返回意图', () => {
  it('公共来源回到休息区，只提供暂停快照', async () => {
    const { router, session } = await setup('/breathe')
    expect(session.authed).toBe(true)
    expect(router.currentRoute.value.fullPath).toBe('/breathe')
    expect(consumeAuthPractice()?.remaining).toBe(41000)
    expect(consumeAuthPractice()).toBeNull()
  })
  it('深链接和认领分支保留目标，不把私有页面练习泄漏到之后的休息页', async () => {
    const { router } = await setup('/todo?task=t#detail', true)
    expect(router.currentRoute.value.path).toBe('/auth/claim')
    expect(authReturnTarget()).toBe('/todo?task=t#detail')
    expect(consumeAuthPractice()).toBeNull()
  })
})
