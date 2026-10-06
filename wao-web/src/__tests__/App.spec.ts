import { describe, it, expect } from 'vitest'
import { nextTick } from 'vue'
import { requireSessionRecovery, resumeSessionRequests } from '@/utils/sessionRecovery'

import { shallowMount } from '@vue/test-utils'
import { toast, useToastHost } from '@/composables/toast'
import { useSessionStore } from '@/stores/session'
import { createPinia } from 'pinia'
import App from '../App.vue'

describe('App', () => {
  it('登录过期与原账号恢复不卸载编辑页面或清空输入', async () => {
    const pinia = createPinia()
    const wrapper = shallowMount(App, {
      global: {
        plugins: [pinia],
        stubs: { RouterView: { template: '<textarea />' } },
        renderStubDefaultSlot: true,
      },
    })
    const session = useSessionStore(pinia)
    const account = { accountId: 'a1', member: { id: 'u1' } } as never
    session.accept(account)
    const editor = wrapper.get('textarea').element
    await wrapper.get('textarea').setValue('还没有保存的草稿')
    requireSessionRecovery()
    await nextTick()
    expect(wrapper.find('session-recovery-stub').exists()).toBe(true)
    expect(wrapper.get('textarea').element).toBe(editor)
    session.accept(account)
    await nextTick()
    expect(wrapper.find('session-recovery-stub').exists()).toBe(false)
    expect(wrapper.get('textarea').element).toBe(editor)
    expect((editor as HTMLTextAreaElement).value).toBe('还没有保存的草稿')
    wrapper.unmount()
    resumeSessionRequests()
  })
  it('只保留公开路由也可安全使用的全局宿主', () => {
    const wrapper = shallowMount(App, {
      global: {
        plugins: [createPinia()],
        stubs: ['RouterView'],
        renderStubDefaultSlot: true,
      },
    })
    expect(wrapper.find('router-view-stub').exists()).toBe(true)
    expect(wrapper.find('toast-host-stub').exists()).toBe(true)
    expect(wrapper.find('ai-assistant-panel-stub').exists()).toBe(false)
    wrapper.unmount()
  })
  it('账号切换立即清除上一账号的持久提示', () => {
    const pinia = createPinia()
    const wrapper = shallowMount(App, {
      global: {
        plugins: [pinia],
        stubs: ['RouterView'],
        renderStubDefaultSlot: true,
      },
    })
    const session = useSessionStore(pinia)
    session.account = { member: { id: 'first' } } as never
    toast('仅上一账号的错误', 'ri-error-warning-line')
    expect(useToastHost().items).toHaveLength(1)
    session.account = { member: { id: 'second' } } as never
    expect(useToastHost().items).toHaveLength(0)
    wrapper.unmount()
  })
})
