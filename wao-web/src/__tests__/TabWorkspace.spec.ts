import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { defineComponent, onBeforeUnmount, ref, watch } from 'vue'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterView, useRoute, useRouter } from 'vue-router'
import TabWorkspace from '@/components/tabs/TabWorkspace.vue'
import TabBar from '@/components/tabs/TabBar.vue'
import { useTabsStore } from '@/stores/tabs'
import { useFloatingTabsStore } from '@/stores/floatingTabs'
import { tabFromRoute } from '@/config/nav'

let wrapper: VueWrapper
const Page = defineComponent({
  setup() {
    const route = useRoute()
    const router = useRouter()
    const draft = ref('')
    const removeGuard = router.beforeEach(() => draft.value !== 'guarded')
    onBeforeUnmount(removeGuard)
    return { route, draft, filter: () => router.replace({ query: { filter: 'done' } }) }
  },
  template:
    '<article><b>{{ route.params.id || "home" }}</b><input v-model="draft" /><span>{{ route.query.filter }}</span><button class="filter" @click="filter">筛选</button></article>',
})
async function setup() {
  const pinia = createPinia()
  const tabs = useTabsStore(pinia)
  const windows = useFloatingTabsStore(pinia)
  const Shell = defineComponent({
    components: { TabWorkspace, TabBar },
    setup() {
      const route = useRoute()
      watch(
        () => route.fullPath,
        () => {
          const tab = tabFromRoute(route)
          if (tab) tabs.open(tab)
        },
        { immediate: true },
      )
    },
    template: '<TabBar /><main><TabWorkspace /></main>',
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/',
        component: Shell,
        children: [
          { path: '', component: Page, meta: { module: 'home', tabKind: 'module' } },
          { path: 'kb/:id', name: 'kb', component: Page, meta: { tabKind: 'kb' } },
          { path: 'projects/:id', name: 'project', component: Page, meta: { tabKind: 'project' } },
        ],
      },
    ],
  })
  await router.push('/projects/one?filter=initial')
  wrapper = mount(RouterView, { attachTo: document.body, global: { plugins: [pinia, router] } })
  await flushPromises()
  return { router, tabs, windows }
}
describe('悬浮页面工作区', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => {
    wrapper?.unmount()
    document.body.innerHTML = ''
  })
  it('知识库切换页面保留实例，关闭页签才释放', async () => {
    const { router, tabs } = await setup()
    await router.push('/kb/one?filter=initial')
    await flushPromises()
    const input = wrapper.get('article input')
    await input.setValue('知识库未完成输入')
    const original = input.element
    await router.push('/')
    await flushPromises()
    expect(document.body.contains(original)).toBe(true)
    await router.push('/kb/one?filter=initial')
    await flushPromises()
    expect(wrapper.findAll('article input').some((item) => item.element === original)).toBe(true)
    expect((original as HTMLInputElement).value).toBe('知识库未完成输入')
    await router.push('/')
    tabs.close('kb:one')
    await flushPromises()
    expect(document.body.contains(original)).toBe(false)
  })
  it('悬浮页保留本页离开确认，同时不阻止底层页面导航', async () => {
    const { router, windows } = await setup()
    await wrapper.get('article input').setValue('guarded')
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/projects/one')
    windows.float('proj:one')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
    const panel = document.querySelector('.floating')!
    ;(panel.querySelector('.filter') as HTMLButtonElement).click()
    await flushPromises()
    expect(panel.querySelector('article span')?.textContent).toBe('initial')
    await router.push('/projects/two')
    expect(router.currentRoute.value.path).toBe('/projects/two')
  })
  it('悬浮、切换底层、修改筛选、收回均保留原输入和页面实例', async () => {
    const { router, windows } = await setup()
    const input = wrapper.get('article input')
    await input.setValue('不能丢失的草稿')
    const original = input.element
    windows.float('proj:one')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/')
    const panel = document.querySelector('.floating')!
    expect(panel.querySelector('input')).toBe(original)
    await router.push('/projects/two')
    await flushPromises()
    expect(panel.querySelector('b')?.textContent).toBe('one')
    ;(panel.querySelector('.filter') as HTMLButtonElement).click()
    await flushPromises()
    expect(panel.textContent).toContain('done')
    expect(router.currentRoute.value.path).toBe('/projects/two')
    ;(panel.querySelector('.window-title button') as HTMLButtonElement).click()
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/projects/one?filter=done')
    expect(document.querySelector('.floating')).toBeNull()
    expect(wrapper.get('article input').element).toBe(original)
    expect((original as HTMLInputElement).value).toBe('不能丢失的草稿')
  })
  it('右键悬浮并允许两个页面同时参考，关闭页签清理窗口', async () => {
    const { router, tabs, windows } = await setup()
    await wrapper.get('.tabbar .tab').trigger('contextmenu')
    await wrapper
      .findAll('.menu-item')
      .find((button) => button.text() === '设为悬浮')!
      .trigger('click')
    await flushPromises()
    expect(windows.keys).toEqual(['proj:one'])
    await router.push('/projects/two')
    windows.float('proj:two')
    await flushPromises()
    expect(document.querySelectorAll('.floating')).toHaveLength(2)
    tabs.close('proj:one')
    await flushPromises()
    expect(windows.keys).toEqual(['proj:two'])
    expect(document.querySelectorAll('.floating')).toHaveLength(1)
  })
  it('拖出栏外悬浮，栏内落点仍排序', async () => {
    const { tabs, windows } = await setup()
    tabs.open({ key: 'proj:two', label: 'two', path: '/projects/two', icon: '', closable: true })
    await flushPromises()
    const items = wrapper.findAll('.tabbar .tab')
    await items[0]!.trigger('dragstart')
    await items[1]!.trigger('drop')
    expect(windows.keys).toHaveLength(0)
    expect(tabs.tabs[tabs.tabs.length - 1]?.key).toBe('proj:one')
    await items[0]!.trigger('dragstart')
    await wrapper.get('main').trigger('drop')
    await flushPromises()
    expect(windows.keys).toEqual(['proj:one'])
  })
})
