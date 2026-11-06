import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import NavigationView from '@/views/NavigationView.vue'
import { useSessionStore } from '@/stores/session'
import { useQuickLinks } from '@/composables/quickLinks'
import { fetchExternalAppRegistry } from '@/api/system'
import type { ExternalAppRegistry } from '@/config/nav'
import { NAVIGATION_CATALOG } from '@/config/navigationCatalog'

vi.mock('@/api/system', () => ({ fetchExternalAppRegistry: vi.fn() }))
vi.mock('@/api/favorites', () => ({
  fetchPreference: vi.fn(async () => JSON.stringify({ version: 2, links: [] })),
  savePreference: vi.fn(),
}))
const apps: ExternalAppRegistry = {
  version: 1,
  apps: Array.from({ length: 24 }, (_, index) => ({
    id: `catalog-${index}`, label: `目录应用 ${index}`, icon: 'ri-apps-line', group: 'TOOLS',
    order: index, visible: true, appUrl: `https://example.com/app-${index}`, healthUrl: '', openMode: 'NEW_PAGE',
  })),
}
const login = (id: string) => useSessionStore().accept({ accountId: id, member: { id, name: id, initial: id, role: '', color: '' }, identities: [], createdAt: '' })
enableAutoUnmount(afterEach)
beforeEach(() => {
  vi.clearAllMocks(); localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia())
  vi.mocked(fetchExternalAppRegistry).mockResolvedValue(apps)
  const links = useQuickLinks(); links.loaded.value = false; links.loading.value = false; links.loadError.value = ''
  login('first')
})
afterEach(() => { document.body.innerHTML = ''; vi.restoreAllMocks() })
async function render() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/navigation')
  const container = document.createElement('div'); container.className = 'content'; document.body.append(container)
  const wrapper = mount(NavigationView, { attachTo: container, global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, container, router }
}

it('离开后恢复搜索、分类、页码和滚动，选择内容保持安全新开行为', async () => {
  const first = await render()
  await first.wrapper.findAll('.domains button').find(button => button.text() === '应用与链接')!.trigger('click')
  await first.wrapper.get('input[type="search"]').setValue('目录应用')
  await first.wrapper.get('[aria-label="下一页"]').trigger('click')
  first.container.dispatchEvent(new Event('wheel'))
  first.container.scrollTop = 420; first.container.dispatchEvent(new Event('scroll'))
  first.wrapper.unmount()
  const second = await render()
  expect(second.wrapper.get<HTMLInputElement>('input[type="search"]').element.value).toBe('目录应用')
  expect(second.wrapper.get('[aria-current="page"]').text()).toBe('2')
  expect(second.wrapper.get('.domains button[aria-pressed="true"]').text()).toBe('全部')
  expect(second.wrapper.get('.hint').text()).toContain('清空搜索后返回「应用与链接」')
  expect(second.container.scrollTop).toBe(420)
  const open = vi.spyOn(window, 'open').mockReturnValue(null)
  await second.wrapper.get('.function-open').trigger('click')
  expect(open).toHaveBeenCalledWith('https://example.com/app-10', '_blank', 'noopener,noreferrer')
})

it('切换账号恢复各自查询，不把另一个账号的页码或关键词带过去', async () => {
  const { wrapper } = await render()
  await wrapper.get('input[type="search"]').setValue('目录应用')
  await wrapper.get('[aria-label="下一页"]').trigger('click')
  login('second'); await flushPromises()
  expect(wrapper.get<HTMLInputElement>('input[type="search"]').element.value).toBe('')
  expect(wrapper.get('.domains button[aria-pressed="true"]').text()).not.toBe('全部')
  await wrapper.get('input[type="search"]').setValue('番茄钟')
  login('first'); await flushPromises()
  expect(wrapper.get<HTMLInputElement>('input[type="search"]').element.value).toBe('目录应用')
  expect(wrapper.get('[aria-current="page"]').text()).toBe('2')
})

it('目录尚未读取时保留保存页码，异步完成后再恢复结果', async () => {
  sessionStorage.setItem('wao-browse-v1:first:first:navigation', JSON.stringify({ query: '目录应用', selected: 'links', catalogPage: 3, scroll: 240 }))
  let resolve!: (value: ExternalAppRegistry) => void
  vi.mocked(fetchExternalAppRegistry).mockImplementationOnce(() => new Promise(done => { resolve = done }))
  const { wrapper, container } = await render()
  expect(wrapper.find('.empty').exists()).toBe(false)
  expect(JSON.parse(sessionStorage.getItem('wao-browse-v1:first:first:navigation')!).catalogPage).toBe(3)
  resolve(apps); await flushPromises()
  expect(wrapper.get('[aria-current="page"]').text()).toBe('3')
  expect(wrapper.findAll('.function-open')).toHaveLength(4)
  expect(container.scrollTop).toBe(240)
})

it('搜索覆盖全部分类；空态清除回到全部并把焦点交回搜索', async () => {
  const { wrapper, container } = await render()
  await wrapper.findAll('.domains button').find(button => button.text() === '应用与链接')!.trigger('click')
  await wrapper.get('input[type="search"]').setValue('番茄钟')
  expect(wrapper.get('.function-open').text()).toContain('番茄钟')
  expect(wrapper.get('.domains button[aria-pressed="true"]').text()).toBe('全部')
  await wrapper.get('input[type="search"]').setValue('完全不匹配的关键词')
  await wrapper.get('.empty button').trigger('click'); await flushPromises()
  expect(wrapper.get<HTMLInputElement>('input[type="search"]').element.value).toBe('')
  expect(document.activeElement).toBe(wrapper.get('input[type="search"]').element)
  expect(wrapper.get('.domains button[aria-pressed="true"]').text()).toBe('全部')
  expect(container.scrollTop).toBe(0)
})

it('目录读取失败后收口失效页码，仍能浏览已知功能并重试', async () => {
  const builtInCount = NAVIGATION_CATALOG.reduce((total, section) => total + section.items.length, 0)
  const savedPage = Math.ceil((builtInCount + apps.apps.length) / 10)
  sessionStorage.setItem('wao-browse-v1:first:first:navigation', JSON.stringify({ query: '', selected: 'all', catalogPage: savedPage, scroll: 0 }))
  vi.mocked(fetchExternalAppRegistry).mockRejectedValueOnce(new Error('目录连接失败'))
  const { wrapper } = await render()
  expect(wrapper.get('[role="alert"]').text()).toContain('目录连接失败')
  expect(wrapper.findAll('.function-open').length).toBeGreaterThan(0)
  expect(JSON.parse(sessionStorage.getItem('wao-browse-v1:first:first:navigation')!).catalogPage).toBe(Math.ceil(builtInCount / 10))
  await wrapper.get('[role="alert"] button').trigger('click'); await flushPromises()
  expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  expect(wrapper.findAll('.function-open').length).toBeGreaterThan(0)
})

it('损坏浏览状态逐项回退，不影响功能目录加载', async () => {
  sessionStorage.setItem('wao-browse-v1:first:first:navigation', JSON.stringify({ query: {}, selected: 'unknown', catalogPage: -7, scroll: -10 }))
  const { wrapper } = await render()
  expect(wrapper.get<HTMLInputElement>('input[type="search"]').element.value).toBe('')
  expect(wrapper.findAll('.function-open').length).toBeGreaterThan(0)
  expect(wrapper.get('.domains button[aria-pressed="true"]').text()).not.toBe('全部')
})
