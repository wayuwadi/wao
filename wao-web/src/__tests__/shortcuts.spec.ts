import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { useShortcuts } from '@/composables/shortcuts'

const ShortcutHost = defineComponent({
  setup() {
    useShortcuts()
  },
  template: '<main><textarea aria-label="正文"></textarea></main>',
})

async function mountHost() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: { template: '<div />' } }],
  })
  router.push('/')
  await router.isReady()
  return mount(ShortcutHost, {
    attachTo: document.body,
    global: { plugins: [router] },
  })
}

describe('全局快捷键', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it.each([
    ['Windows / Linux', { ctrlKey: true }],
    ['macOS', { metaKey: true }],
  ])('%s 的保存组合键不会触发浏览器另存为', async (_platform, modifier) => {
    const wrapper = await mountHost()
    const event = new KeyboardEvent('keydown', {
      key: 's',
      bubbles: true,
      cancelable: true,
      ...modifier,
    })

    wrapper.find('textarea').element.dispatchEvent(event)

    expect(event.defaultPrevented).toBe(true)
    wrapper.unmount()
  })

  it('普通输入 s 不受影响', async () => {
    const wrapper = await mountHost()
    const event = new KeyboardEvent('keydown', {
      key: 's',
      bubbles: true,
      cancelable: true,
    })

    wrapper.find('textarea').element.dispatchEvent(event)

    expect(event.defaultPrevented).toBe(false)
    wrapper.unmount()
  })
})
