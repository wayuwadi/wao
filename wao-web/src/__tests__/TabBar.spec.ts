import { beforeEach, describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
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
  it('页签使用独立切换与关闭按钮，键盘关闭后焦点回到剩余页签', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const tabs = useTabsStore()
    tabs.open({ key: 'files', label: '文件库', icon: 'ri-file-line', path: '/files', closable: true })
    const router = testRouter()
    await router.push('/files')
    const wrapper = mount(TabBar, { attachTo: document.body, global: { plugins: [pinia, router] } })
    expect(wrapper.get('[aria-label="切换到文件库"]').element.tagName).toBe('BUTTON')
    expect(wrapper.get('[aria-label="关闭页签：文件库"]').element.parentElement?.tagName).toBe('DIV')
    wrapper.get('[aria-label="关闭页签：文件库"]').element.dispatchEvent(new MouseEvent('click', { bubbles: true, detail: 0 }))
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
    expect(document.activeElement).toBe(wrapper.get('.home-tab').element)
    wrapper.unmount()
  })

  beforeEach(() => {
    localStorage.clear()
  })

  it('首页固定在滚动容器外，其余页签仍放在滚动区域', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const tabs = useTabsStore()
    tabs.open({
      key: 'proj:p1', label: '项目一', icon: 'ri-stack-line', path: '/projects/p1', closable: true,
    })
    const wrapper = mount(TabBar, { global: { plugins: [pinia, testRouter()] } })

    expect(wrapper.get('.home-tab').text()).toContain('首页')
    expect(wrapper.get('.home-tab').element.parentElement).toBe(wrapper.get('.tabbar-shell').element)
    expect(wrapper.get('.tabbar').text()).not.toContain('首页')
    expect(wrapper.get('.tabbar').text()).toContain('项目一')
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

  it('右键可重命名并关闭所有非置顶页签', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const tabs = useTabsStore()
    tabs.open({ key: 'proj:p1', label: '项目一', icon: 'ri-stack-line', path: '/projects/p1', closable: true, pinned: true })
    tabs.open({ key: 'proj:p2', label: '项目二', icon: 'ri-stack-line', path: '/projects/p2', closable: true })
    const wrapper = mount(TabBar, { global: { plugins: [pinia, testRouter()] } })

    await wrapper.findAll('.tab')[1]!.trigger('contextmenu', { clientX: 80, clientY: 60 })
    await wrapper.findAll('.menu-item').find((item) => item.text().includes('重命名页签'))!.trigger('click')
    await wrapper.get('#tab-rename-input').setValue('核心项目')
    await wrapper.get('.rename-form').trigger('submit')
    expect(tabs.tabs[1]?.label).toBe('核心项目')

    await wrapper.findAll('.tab')[2]!.trigger('contextmenu', { clientX: 90, clientY: 60 })
    const closeUnpinned = wrapper.findAll('.menu-item').find((item) => item.text().includes('关闭非置顶'))!
    await closeUnpinned.trigger('click')
    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'proj:p1'])
    wrapper.unmount()
  })
})
