import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import QuietCompanionHost from '@/components/companions/QuietCompanionHost.vue'
import { captureCompanionTicket, companionEditing, companionResponse, resetCompanionContext, respondWithCompanion, setCompanionQuiet } from '@/composables/quietCompanions'
vi.mock('@/components/companions/companionSpace', () => ({ companionSpace: () => ({ left: '300px', top: '200px' }) }))
vi.mock('@/components/companions/CompanionPortrait.vue', () => ({ __esModule: true, default: { template: '<canvas />' } }))
afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks(); vi.useRealTimers() })
it('开始输入立即收起且不补播，Escape 和切页丢弃回应', async () => {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: {} }, { path: '/notes', component: {} }] })
  await router.push('/')
  const w = mount(QuietCompanionHost, { attachTo: document.body, global: { plugins: [createPinia(), router] } })
  document.body.insertAdjacentHTML('beforeend', '<button id="action">保存</button><input id="editor">')
  const button = document.querySelector<HTMLButtonElement>('#action')!, input = document.querySelector<HTMLInputElement>('#editor')!
  vi.spyOn(button, 'getBoundingClientRect').mockReturnValue(new DOMRect(300, 300, 80, 30))
  resetCompanionContext(crypto.randomUUID()); setCompanionQuiet(false); button.focus()
  respondWithCompanion('note', captureCompanionTicket(button)); await flushPromises()
  expect(document.querySelector('.quiet-companion')).not.toBeNull()
  input.focus(); await flushPromises()
  expect(companionEditing.value).toBe(true)
  expect(document.querySelector('.quiet-companion')).toBeNull()
  button.focus(); await flushPromises()
  expect(companionEditing.value).toBe(false)
  expect(companionResponse.value).toBeNull()
  respondWithCompanion('task', captureCompanionTicket(button)); await flushPromises()
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })); await flushPromises()
  expect(document.querySelector('.quiet-companion')).toBeNull()
  respondWithCompanion('empty', captureCompanionTicket(button)); await flushPromises()
  await router.push('/notes'); await flushPromises()
  expect(document.querySelector('.quiet-companion')).toBeNull()
  w.unmount()
})
