import { afterEach, describe, expect, it } from 'vitest'
import { defineComponent, nextTick, ref } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import FloatingChat from '@/components/chat/FloatingChat.vue'
import { useUiStore } from '@/stores/ui'

const ChatStub = defineComponent({
  name: 'ChatView',
  props: {
    visible: Boolean,
    reviewId: { type: String, default: '' },
    reviewRequestVersion: { type: Number, default: 0 },
  },
  emits: ['minimize', 'unread-change'],
  setup() {
    const draft = ref('')
    return { draft }
  },
  template: `
    <div class="chat-view-stub">
      <header class="chat-header"><h1>共享聊天</h1></header>
      <input v-model="draft" aria-label="聊天草稿" />
      <button class="stub-unread" @click="$emit('unread-change', 3)">未读</button>
      <button class="stub-minimize" @click="$emit('minimize')">最小化</button>
    </div>
  `,
})

let wrappers: VueWrapper[] = []

function bodyElement<T extends Element>(selector: string): T {
  const element = document.body.querySelector<T>(selector)
  if (!element) throw new Error(`页面中缺少 ${selector}`)
  return element
}

async function mountFloating(path = '/') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push(path)
  await router.isReady()
  const pinia = createPinia()
  const wrapper = mount(FloatingChat, {
    attachTo: document.body,
    global: { plugins: [pinia, router], stubs: { ChatView: ChatStub } },
  })
  wrappers.push(wrapper)
  await nextTick()
  return { wrapper, ui: useUiStore(pinia) }
}

describe('悬浮聊天入口', () => {
  afterEach(() => {
    wrappers.forEach((wrapper) => wrapper.unmount())
    wrappers = []
    document.body.innerHTML = ''
  })

  it('点击右上角入口打开，点击框外最小化且重新打开仍保留输入', async () => {
    const { wrapper, ui } = await mountFloating()

    await wrapper.get('.chat-entry').trigger('click')
    const draft = bodyElement<HTMLInputElement>('input[aria-label="聊天草稿"]')
    draft.value = '还没发完'
    draft.dispatchEvent(new Event('input', { bubbles: true }))
    await nextTick()
    expect(ui.chatOpen).toBe(true)

    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await nextTick()
    expect(ui.chatOpen).toBe(false)

    await wrapper.get('.chat-entry').trigger('click')
    expect(bodyElement<HTMLInputElement>('input[aria-label="聊天草稿"]').value).toBe('还没发完')
  })

  it('展示真实未读数量，并支持框内按钮最小化', async () => {
    const { wrapper, ui } = await mountFloating()

    bodyElement<HTMLButtonElement>('.stub-unread').click()
    await nextTick()
    expect(wrapper.get('.chat-badge').text()).toBe('3')

    ui.openChat()
    bodyElement<HTMLButtonElement>('.stub-minimize').click()
    await nextTick()
    expect(ui.chatOpen).toBe(false)
  })

  it('兼容旧聊天复盘地址带来的首页查询参数', async () => {
    const { ui } = await mountFloating('/?chat=1&review=cr-shared')

    expect(ui.chatOpen).toBe(true)
    expect(ui.chatReviewId).toBe('cr-shared')
    expect(ui.chatReviewRequestVersion).toBe(1)
  })

  it('桌面端可从聊天标题栏拖动窗口并保存位置', async () => {
    const { ui } = await mountFloating()
    ui.openChat()
    await nextTick()
    const panel = bodyElement<HTMLElement>('.floating-chat')
    const header = bodyElement<HTMLElement>('.chat-header')
    Object.defineProperties(panel, {
      offsetWidth: { configurable: true, value: 600 },
      offsetHeight: { configurable: true, value: 600 },
      getBoundingClientRect: { configurable: true, value: () => ({ left: 300, top: 80, width: 600, height: 600 }) },
    })
    Object.defineProperties(header, {
      setPointerCapture: { configurable: true, value: () => undefined },
      hasPointerCapture: { configurable: true, value: () => true },
      releasePointerCapture: { configurable: true, value: () => undefined },
    })
    const pointer = (type: string, x: number, y: number) => {
      const event = new MouseEvent(type, { bubbles: true, button: 0, clientX: x, clientY: y })
      Object.defineProperty(event, 'pointerId', { value: 9 })
      return event
    }
    header.dispatchEvent(pointer('pointerdown', 330, 100))
    window.dispatchEvent(pointer('pointermove', 430, 180))
    window.dispatchEvent(pointer('pointerup', 430, 180))
    await nextTick()

    expect(panel.getAttribute('style')).toContain('left: 400px')
    expect(localStorage.getItem('wao-floating-chat-position-v1')).toContain('400')
  })
})
