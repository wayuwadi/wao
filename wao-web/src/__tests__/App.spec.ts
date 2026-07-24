import { describe, it, expect } from 'vitest'

import { shallowMount } from '@vue/test-utils'
import App from '../App.vue'

describe('App', () => {
  it('renders the application shell', () => {
    const wrapper = shallowMount(App, {
      global: {
        stubs: ['RouterView'],
      },
    })
    expect(wrapper.text()).toContain('WAO')
    expect(wrapper.text()).toContain('PHASE 0')
  })
})
