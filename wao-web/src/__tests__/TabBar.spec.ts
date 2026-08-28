import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import TabBar from '@/components/tabs/TabBar.vue'
import { useTabsStore } from '@/stores/tabs'

function testRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
}

describe('TabBar', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('可从右键菜单置顶页签，并在关闭全部后保留它', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const tabs = useTabsStore()
    tabs.open({
      key: 'proj:p1',
      label: '常用项目',
      icon: 'ri-stack-line',
      path: '/projects/p1',
      closable: true,
    })
    tabs.open({
      key: 'proj:p2',
      label: '临时项目',
      icon: 'ri-stack-line',
      path: '/projects/p2',
      closable: true,
    })
    const router = testRouter()
    const wrapper = mount(TabBar, { global: { plugins: [pinia, router] } })
    const commonTab = wrapper.findAll('.tab').find((tab) => tab.text().includes('常用项目'))!

    await commonTab.trigger('contextmenu', { clientX: 100, clientY: 80 })
    const pinButton = wrapper
      .findAll('.menu-item')
      .find((item) => item.text().includes('置顶页签'))!
    await pinButton.trigger('click')

    expect(tabs.tabs.find((tab) => tab.key === 'proj:p1')?.pinned).toBe(true)
    expect(commonTab.classes()).toContain('pinned')
    expect(commonTab.find('.tab-pin').attributes('title')).toBe('已置顶，批量关闭时保留')

    const temporaryTab = wrapper.findAll('.tab').find((tab) => tab.text().includes('临时项目'))!
    await temporaryTab.trigger('contextmenu', { clientX: 120, clientY: 80 })
    const closeAllButton = wrapper
      .findAll('.menu-item')
      .find((item) => item.text().includes('关闭全部'))!
    await closeAllButton.trigger('click')

    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'proj:p1'])
    expect(wrapper.text()).toContain('常用项目')
    expect(wrapper.text()).not.toContain('临时项目')
  })

  it('已置顶页签的菜单可取消置顶', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const tabs = useTabsStore()
    tabs.open({
      key: 'proj:p1',
      label: '常用项目',
      icon: 'ri-stack-line',
      path: '/projects/p1',
      closable: true,
      pinned: true,
    })
    const wrapper = mount(TabBar, { global: { plugins: [pinia, testRouter()] } })
    const commonTab = wrapper.findAll('.tab').find((tab) => tab.text().includes('常用项目'))!

    await commonTab.trigger('contextmenu', { clientX: 100, clientY: 80 })
    const unpinButton = wrapper
      .findAll('.menu-item')
      .find((item) => item.text().includes('取消置顶'))!
    await unpinButton.trigger('click')

    expect(tabs.tabs.find((tab) => tab.key === 'proj:p1')?.pinned).toBe(false)
    expect(commonTab.find('.tab-pin').exists()).toBe(false)
  })
})
