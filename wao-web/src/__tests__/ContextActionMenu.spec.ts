import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { nextTick } from 'vue'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'

async function setup() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
  await router.push('/')
  await router.isReady()
  const trigger = document.createElement('button')
  trigger.textContent = '更多操作'
  document.body.append(trigger)
  const wrapper = mount(ContextActionMenu, { attachTo: document.body, global: { plugins: [router] } })
  return { wrapper, trigger }
}

afterEach(() => { document.body.replaceChildren() })

describe('对象右键菜单', () => {
  it('只接管有业务菜单的对象，并在窗口边缘显示可执行操作', async () => {
    const { wrapper, trigger } = await setup()
    const run = vi.fn()
    const plain = document.createElement('span')
    document.body.append(plain)
    const nativeEvent = new MouseEvent('contextmenu', { bubbles: true, cancelable: true })
    expect(plain.dispatchEvent(nativeEvent)).toBe(true)
    expect(nativeEvent.defaultPrevented).toBe(false)

    trigger.addEventListener('contextmenu', (event) => { void wrapper.vm.openAt(event, '测试任务', [{ id: 'open', label: '打开任务', run }]) })
    const event = new MouseEvent('contextmenu', { bubbles: true, cancelable: true, clientX: innerWidth - 1, clientY: innerHeight - 1 })
    expect(trigger.dispatchEvent(event)).toBe(false)
    await nextTick()
    await nextTick()
    const menu = document.body.querySelector<HTMLElement>('[role="menu"]')
    expect(menu?.getAttribute('aria-label')).toBe('测试任务的操作')
    expect(menu?.style.visibility).toBe('visible')
    const item = menu?.querySelector<HTMLButtonElement>('button')
    item?.click()
    await nextTick()
    expect(run).toHaveBeenCalledOnce()
    expect(document.body.querySelector('[role="menu"]')).toBeNull()
    wrapper.unmount()
  })

  it('键盘打开后 Esc 关闭并将焦点还给触发对象', async () => {
    const { wrapper, trigger } = await setup()
    const event = new KeyboardEvent('keydown', { key: 'F10', shiftKey: true, bubbles: true, cancelable: true })
    trigger.addEventListener('keydown', (keyEvent) => { void wrapper.vm.openAt(keyEvent, '测试对象', [{ id: 'open', label: '打开', run: vi.fn() }]) })
    trigger.dispatchEvent(event)
    await nextTick()
    await nextTick()
    const menu = document.body.querySelector<HTMLElement>('[role="menu"]')
    expect(document.activeElement).toBe(menu?.querySelector('button'))
    menu?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }))
    await nextTick()
    expect(document.body.querySelector('[role="menu"]')).toBeNull()
    expect(document.activeElement).toBe(trigger)
    wrapper.unmount()
  })
})
