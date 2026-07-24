import { describe, it, expect } from 'vitest'

import { shallowMount } from '@vue/test-utils'
import App from '../App.vue'

describe('App', () => {
  it('renders the router outlet', () => {
    const wrapper = shallowMount(App, {
      global: {
        stubs: ['RouterView'],
      },
    })
    expect(wrapper.find('router-view-stub').exists()).toBe(true)
  })
})
