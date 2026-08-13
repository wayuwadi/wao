import { describe, it, expect } from 'vitest'

import { shallowMount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import App from '../App.vue'

describe('App', () => {
  it('只保留公开路由也可安全使用的全局宿主', () => {
    const wrapper = shallowMount(App, {
      global: {
        plugins: [createPinia()],
        stubs: ['RouterView'],
      },
    })
    expect(wrapper.find('router-view-stub').exists()).toBe(true)
    expect(wrapper.find('toast-host-stub').exists()).toBe(true)
    expect(wrapper.find('ai-assistant-panel-stub').exists()).toBe(false)
  })
})
