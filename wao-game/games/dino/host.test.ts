// @vitest-environment jsdom
import { defineComponent, nextTick, ref } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import GameHost from '../../host/GameHost.vue'
import { MemoryStore } from '../../host/memory'
import { createGameMemory, gameMemoryKey } from '../../host/useGameMemory'
import { envelope } from '../../protocol/dino'

function setup() {
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() {} })
  vi.stubGlobal('BroadcastChannel', undefined)
  return { provide: { [gameMemoryKey as symbol]: createGameMemory(new MemoryStore(new IDBFactory())) } }
}
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks(); document.body.innerHTML = '' })

describe('独立游戏容器', () => {
  it('恢复父页面布局后才滚回原处；记忆面板通知外部焦点隔离', async () => {
    const Parent = defineComponent({ components: { GameHost }, setup: () => ({ immersive: ref(false) }),
      template: '<div><GameHost active source="/game/index.html" @immersion="immersive=$event"/><footer v-if="!immersive" id="restored-footer">页面底部</footer></div>' })
    const wrapper = mount(Parent, { attachTo: document.body, global: setup() })
    await flushPromises()
    vi.spyOn(window, 'scrollY', 'get').mockReturnValue(79)
    const restored: boolean[] = []
    const scroll = vi.spyOn(window, 'scrollTo').mockImplementation(() => { restored.push(!!document.querySelector('#restored-footer')) })
    const host = wrapper.findComponent(GameHost)
    await host.findAll('.host-bar button')[1]!.trigger('click')
    expect(document.querySelector('#restored-footer')).toBeNull()
    await host.findAll('.host-bar button')[1]!.trigger('click'); await nextTick()
    expect(scroll).toHaveBeenCalledWith({ top: 79, behavior: 'instant' })
    expect(restored).toEqual([true])
    await host.get('.memory-open').trigger('click'); await flushPromises()
    expect(host.emitted('memoryPanel')).toEqual([[true]])
    expect(host.get('.frame-wrap').attributes()).toHaveProperty('inert')
    await host.get('.memory-close').trigger('click')
    expect(host.emitted('memoryPanel')).toEqual([[true], [false]])
    wrapper.unmount()
  })
  it('只接受当前iframe同源、匹配实例的握手', async () => {
    const wrapper = mount(GameHost, { attachTo: document.body, props: { active: true, source: '/game/index.html' }, global: setup() })
    await flushPromises()
    await vi.waitFor(() => expect(wrapper.find('iframe').exists()).toBe(true))
    const frame = wrapper.get('iframe').element as HTMLIFrameElement
    const instance = new URL(frame.src).searchParams.get('instance')!
    const post = vi.spyOn(frame.contentWindow!, 'postMessage').mockImplementation(() => {})
    const data = envelope({ type: 'ready', payload: { rulesVersion: 1 } }, instance, 1)
    window.dispatchEvent(new MessageEvent('message', { data, origin: 'https://untrusted.invalid', source: frame.contentWindow }))
    window.dispatchEvent(new MessageEvent('message', { data, origin: location.origin, source: window }))
    expect(post).not.toHaveBeenCalled()
    window.dispatchEvent(new MessageEvent('message', { data, origin: location.origin, source: frame.contentWindow }))
    await nextTick()
    expect(post.mock.calls[0]?.[0]).toMatchObject({ type: 'init', instanceId: instance })
    const count = post.mock.calls.length
    window.dispatchEvent(new MessageEvent('message', { data, origin: location.origin, source: frame.contentWindow }))
    expect(post.mock.calls).toHaveLength(count)
    wrapper.unmount()
  })
})
