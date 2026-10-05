import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import TabPage from '@/components/tabs/TabPage.vue'
import { homeJourneyExperience, rememberHomeJourney } from '@/composables/homeJourneyExperience'
import { completeRestJourney, restJourneyRequested, restSelectionSerial, restArrivalSerial } from '@/composables/restJourney'
import { completeWritingJourney, writingJourneyRequested, writingFocusPending } from '@/composables/writingJourney'

let wrapper: VueWrapper | undefined
const interactionStub = { template: '<span />', methods: { clear() {}, settle() {} } }
async function render(snapshot = false) {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/', component: HomeView },
    { path: '/notes', component: { template: '<p>小记</p>' } },
    { path: '/breathe', component: { template: '<p>休息选择</p>' } },
  ] })
  await router.push('/')
  wrapper = mount(snapshot ? TabPage : HomeView, { ...(snapshot ? { props: { pageKey: 'home', location: router.currentRoute.value, floating: false } } : {}), global: { plugins: [createPinia(), router], stubs: {
    HomeOverviewCard: true, WeatherPanel: true, HomeCompanions: interactionStub,
    HomeSurprises: interactionStub, HomeHiddenWorld: interactionStub, HomePointerPlay: interactionStub,
  } } })
  return router
}
beforeEach(() => {
  localStorage.clear()
  homeJourneyExperience.value = { rest: false, writing: false }
  completeWritingJourney(); completeRestJourney(false)
  writingFocusPending.value = 0; restSelectionSerial.value = 0; restArrivalSerial.value = 0
})
afterEach(() => { wrapper?.unmount(); vi.restoreAllMocks() })

it('首次保留完整旅程，取消不误记，成功后提供重播', async () => {
  await render()
  await wrapper!.get('.writing-entrance').trigger('click')
  expect(writingJourneyRequested.value).not.toBeNull()
  completeWritingJourney()
  expect(homeJourneyExperience.value.writing).toBe(false)
  completeWritingJourney(true); await flushPromises()
  expect(wrapper!.find('[aria-label="重看写作旅程"]').exists()).toBe(true)
  completeRestJourney(false)
  expect(homeJourneyExperience.value.rest).toBe(false)
  completeRestJourney(true)
  expect(homeJourneyExperience.value.rest).toBe(true)
  expect(JSON.parse(localStorage.getItem('wao-home-journey-experience-v1')!)).toEqual({ rest: true, writing: true })
})
it('日常写作直接交出焦点请求，重播仍请求完整动画', async () => {
  rememberHomeJourney('writing')
  const router = await render()
  await wrapper!.get('.writing-entrance').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/notes')
  expect(writingJourneyRequested.value).toBeNull()
  expect(writingFocusPending.value).toBeGreaterThan(0)
  await router.push('/')
  await wrapper!.get('[aria-label="重看写作旅程"]').trigger('click')
  expect(writingJourneyRequested.value).not.toBeNull()
})
it('真实工作区页签的首页路由快照不阻止写作焦点交接', async () => {
  rememberHomeJourney('writing')
  const router = await render(true)
  await wrapper!.get('.writing-entrance').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/notes')
  expect(writingFocusPending.value).toBeGreaterThan(0)
  expect(wrapper!.find('[role="alert"]').exists()).toBe(false)
})
it('日常休息直接到选择不触发呼吸引导，重播保留黑洞旅程', async () => {
  rememberHomeJourney('rest')
  const router = await render()
  await wrapper!.get('.rest-entrance').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/breathe')
  expect(restJourneyRequested.value).toBe(false)
  expect(restSelectionSerial.value).toBe(1)
  expect(restArrivalSerial.value).toBe(0)
  await router.push('/')
  await wrapper!.get('[aria-label="重看休息旅程"]').trigger('click')
  expect(restJourneyRequested.value).toBe(true)
})
it('失败不交出焦点且允许重试，存储失败不阻断日常入口', async () => {
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('quota') })
  rememberHomeJourney('writing')
  const router = await render()
  const push = vi.spyOn(router, 'push').mockRejectedValueOnce(new Error('offline'))
  await wrapper!.get('.writing-entrance').trigger('click'); await flushPromises()
  expect(wrapper!.get('[role="alert"]').text()).toContain('已有草稿仍保留')
  expect(writingFocusPending.value).toBe(0)
  expect(wrapper!.get('.writing-entrance').attributes('disabled')).toBeUndefined()
  await wrapper!.get('.writing-entrance').trigger('click'); await flushPromises()
  expect(push).toHaveBeenCalledTimes(2)
  expect(router.currentRoute.value.path).toBe('/notes')
  expect(writingFocusPending.value).toBeGreaterThan(0)
})
