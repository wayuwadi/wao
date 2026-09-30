import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MenuGroupPicker from '@/components/nav/MenuGroupPicker.vue'
import { useNavStore } from '@/stores/nav'
import { fetchNavigationLayout, saveNavigationLayout } from '@/api/navigationLayout'
import { fetchBuiltinMenuRegistry, fetchExternalAppRegistry } from '@/api/system'
import { DEFAULT_BUILTIN_MENUS, DEFAULT_EXTERNAL_APPS } from '@/config/nav'

vi.mock('@/api/navigationLayout', () => ({ fetchNavigationLayout: vi.fn<typeof fetchNavigationLayout>(), saveNavigationLayout: vi.fn<typeof saveNavigationLayout>() }))
vi.mock('@/api/system', () => ({ fetchBuiltinMenuRegistry: vi.fn<typeof fetchBuiltinMenuRegistry>(), fetchExternalAppRegistry: vi.fn<typeof fetchExternalAppRegistry>() }))
vi.mock('@/composables/toast', () => ({ toast: vi.fn<() => void>() }))

describe('菜单分组选择弹窗', () => {
  let wrapper: VueWrapper | undefined
  beforeEach(() => {
    vi.resetAllMocks()
    document.body.innerHTML = ''
    document.body.style.overflow = ''
    localStorage.clear()
    setActivePinia(createPinia())
    vi.mocked(fetchNavigationLayout).mockResolvedValue({ revision: 'original', layout: null })
    vi.mocked(fetchBuiltinMenuRegistry).mockResolvedValue({ version: 27, menus: DEFAULT_BUILTIN_MENUS.map((item) => ({
      ...item,
      ...(item.id === 'projects' ? { group: 'PERSONAL', placement: 'MENU' as const, visible: true } : {}),
      ...(item.id === 'calendar' ? { group: 'PERSONAL', placement: 'TOP' as const, visible: true } : {}),
      ...(item.id === 'files' ? { group: 'TOOLS', placement: 'MENU' as const, visible: false } : {}),
      ...(item.id === 'skills' ? { group: 'PUBLIC', placement: 'MENU' as const, visible: true } : {}),
    })) })
    vi.mocked(fetchExternalAppRegistry).mockResolvedValue({ version: 6, apps: DEFAULT_EXTERNAL_APPS.map((item) => ({
      ...item,
      ...(item.id === 'getnote' ? { label: '团队资料检索与共享工作空间', group: 'TOOLS', placement: 'DOCK' as const, visible: true } : {}),
    })) })
    vi.mocked(saveNavigationLayout).mockImplementation(async (_revision, layout) => ({ revision: 'saved', layout }))
  })
  afterEach(() => { wrapper?.unmount(); wrapper = undefined })

  async function open() {
    await useNavStore().loadMenus()
    wrapper = mount(MenuGroupPicker, { props: { groupId: 'PUBLIC' }, attachTo: document.body })
    await flushPromises()
    return useNavStore()
  }
  function checkbox(label: string) {
    return [...document.querySelectorAll<HTMLInputElement>('.picker-option input')]
      .find((input) => input.getAttribute('aria-label') === label)!
  }
  async function search(value: string) {
    const input = document.querySelector<HTMLInputElement>('[aria-label="搜索菜单"]')!
    input.value = value
    input.dispatchEvent(new Event('input', { bubbles: true }))
    await flushPromises()
  }
  function filter(label: string) {
    return [...document.querySelectorAll<HTMLButtonElement>('.picker-filters button')].find((button) => button.textContent?.includes(label))!
  }
  async function click(selector: string) {
    await flushPromises()
    document.querySelector<HTMLButtonElement>(selector)!.click()
    await flushPromises()
  }

  it('展示真实当前位置和已添加状态，按功能别名搜索并区分搜索空结果', async () => {
    const nav = await open()
    expect(document.activeElement?.getAttribute('aria-label')).toBe('搜索菜单')
    expect(checkbox(nav.itemMeta('skills')!.label).disabled).toBe(true)
    expect(checkbox('日历').closest('label')?.textContent).toContain('顶部菜单')
    expect(checkbox('团队资料检索与共享工作空间').closest('label')?.textContent).toContain('底部图标区')
    await search('HUB')
    expect(document.querySelector('.picker-option-title')?.textContent).toBe(nav.itemMeta('skills')!.label)
    await search('不存在的菜单关键词')
    expect(document.querySelector('.picker-empty')?.textContent).toContain('没有找到匹配的菜单')
    expect(document.querySelector<HTMLInputElement>('[aria-label="搜索菜单"]')!.value).toBe('不存在的菜单关键词')
    await click('.picker-empty button')
    expect(document.querySelectorAll('.picker-option').length).toBeGreaterThan(1)
    expect(saveNavigationLayout).not.toHaveBeenCalled()
  })

  it('跨搜索和筛选保留选择，确认后以一次请求移动菜单并保留其它配置', async () => {
    const nav = await open()
    const before = nav.currentLayout()
    await search('项目')
    checkbox('项目').click()
    await search('团队资料')
    checkbox('团队资料检索与共享工作空间').click()
    await search('')
    filter('未显示').click()
    await flushPromises()
    checkbox(nav.itemMeta('files')!.label).click()
    await flushPromises()
    expect(document.querySelector('.picker-selection')?.textContent).toContain('已选 3 项')
    expect(saveNavigationLayout).not.toHaveBeenCalled()
    await click('.picker-submit')
    expect(saveNavigationLayout).toHaveBeenCalledTimes(1)
    const [revision, saved] = vi.mocked(saveNavigationLayout).mock.calls[0]!
    expect(revision).toBe('original')
    const ids = ['projects', 'external:getnote', 'files']
    for (const id of ids) expect(saved.items.find((item) => item.id === id)).toMatchObject({ group: 'PUBLIC', visible: true, placement: 'MENU' })
    expect(new Set(saved.items.map((item) => item.id)).size).toBe(saved.items.length)
    expect(saved.items.filter((item) => !ids.includes(item.id) && item.group !== 'PUBLIC'))
      .toEqual(before.items.filter((item) => !ids.includes(item.id) && item.group !== 'PUBLIC'))
    const addedOrders = ids.map((id) => saved.items.find((item) => item.id === id)!.order)
    expect(addedOrders[1]).toBe(addedOrders[0]! + 10)
    expect(addedOrders[2]).toBe(addedOrders[1]! + 10)
    expect(saved.groups).toEqual(before.groups)
    expect(wrapper!.emitted('close')).toHaveLength(1)
  })

  it('仅选择当前搜索结果，取消当前选择时保留其它筛选下已选菜单', async () => {
    await open()
    checkbox('项目').click()
    await search('团队资料')
    await click('.picker-list-heading button')
    expect(checkbox('团队资料检索与共享工作空间').checked).toBe(true)
    expect(document.querySelector('.picker-selection')?.textContent).toContain('已选 2 项')
    await click('.picker-list-heading button')
    expect(checkbox('团队资料检索与共享工作空间').checked).toBe(false)
    expect(document.querySelector('.picker-selection')?.textContent).toContain('已选 1 项')
    await search('')
    expect(checkbox('项目').checked).toBe(true)
  })

  it('保存中冻结列表和已选数量，阻止重复提交与关闭', async () => {
    await open()
    let finish!: () => void
    vi.mocked(saveNavigationLayout).mockImplementationOnce((_revision, layout) => new Promise((resolve) => {
      finish = () => resolve({ revision: 'saved', layout })
    }))
    checkbox('项目').click()
    await flushPromises()
    const count = document.querySelectorAll('.picker-option').length
    await click('.picker-submit')
    expect(document.querySelectorAll('.picker-option')).toHaveLength(count)
    expect(document.querySelector('.picker-selection')?.textContent).toContain('已选 1 项')
    expect(checkbox('项目').checked).toBe(true)
    expect(checkbox('项目').disabled).toBe(true)
    expect(document.querySelector<HTMLButtonElement>('.picker-cancel')!.disabled).toBe(true)
    document.querySelector('.group-picker')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await click('.picker-submit')
    expect(wrapper!.emitted('close')).toBeUndefined()
    expect(saveNavigationLayout).toHaveBeenCalledTimes(1)
    finish()
    await flushPromises()
    expect(wrapper!.emitted('close')).toHaveLength(1)
  })

  it('网络失败整批回滚，保留搜索和选择后可以原样重试', async () => {
    const nav = await open()
    vi.mocked(saveNavigationLayout).mockRejectedValueOnce(new Error('网络不可用'))
    const before = nav.currentLayout()
    await search('项目')
    checkbox('项目').click()
    await click('.picker-submit')
    expect(nav.currentLayout()).toEqual(before)
    expect(document.querySelector('.picker-error')?.textContent).toContain('网络不可用')
    expect(checkbox('项目').checked).toBe(true)
    expect(document.querySelector<HTMLInputElement>('[aria-label="搜索菜单"]')!.value).toBe('项目')
    expect(wrapper!.emitted('close')).toBeUndefined()
    await click('.picker-submit')
    expect(saveNavigationLayout).toHaveBeenCalledTimes(2)
    expect(nav.menuConfig('projects')).toMatchObject({ group: 'PUBLIC', visible: true })
    expect(wrapper!.emitted('close')).toHaveLength(1)
  })

  it('版本冲突不覆盖另一设备布局，显式读取后保留选择并使用新版本重试', async () => {
    const nav = await open()
    vi.mocked(saveNavigationLayout).mockRejectedValueOnce(Object.assign(new Error('另一设备已修改导航'), { status: 409 }))
    checkbox('项目').click()
    await click('.picker-submit')
    expect(nav.layoutConflict).toBe(true)
    expect(document.querySelector<HTMLButtonElement>('.picker-submit')!.disabled).toBe(true)
    const remote = nav.currentLayout()
    remote.items.find((item) => item.id === 'calendar')!.label = '另一设备的日历名称'
    vi.mocked(fetchNavigationLayout).mockResolvedValueOnce({ revision: 'remote-revision', layout: remote })
    await click('.picker-error button')
    expect(nav.layoutConflict).toBe(false)
    expect(checkbox('项目').checked).toBe(true)
    await click('.picker-submit')
    const [revision, payload] = vi.mocked(saveNavigationLayout).mock.calls[1]!
    expect(revision).toBe('remote-revision')
    expect(payload.items.find((item) => item.id === 'calendar')!.label).toBe('另一设备的日历名称')
  })

  it('键盘焦点留在弹窗，Escape 关闭且卸载恢复页面滚动', async () => {
    document.body.style.overflow = 'auto'
    await open()
    expect(document.body.style.overflow).toBe('hidden')
    const close = document.querySelector<HTMLButtonElement>('[aria-label="关闭添加菜单"]')!
    const cancel = document.querySelector<HTMLButtonElement>('.picker-cancel')!
    close.focus()
    close.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }))
    expect(document.activeElement).toBe(cancel)
    cancel.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }))
    expect(document.activeElement).toBe(close)
    close.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    expect(wrapper!.emitted('close')).toHaveLength(1)
    wrapper!.unmount()
    wrapper = undefined
    expect(document.body.style.overflow).toBe('auto')
    expect(saveNavigationLayout).not.toHaveBeenCalled()
  })

  it('冲突恢复时读取失败仍有重试入口，重新读取后保留选择', async () => {
    const nav = await open()
    vi.mocked(saveNavigationLayout).mockRejectedValueOnce(Object.assign(new Error('布局冲突'), { status: 409 }))
    checkbox('项目').click()
    await click('.picker-submit')
    vi.mocked(fetchNavigationLayout).mockRejectedValueOnce(new Error('最新布局读取失败'))
    await click('.picker-error button')
    expect(document.querySelector('.picker-error')?.textContent).toContain('最新布局读取失败')
    expect(document.querySelector('.picker-error button')).not.toBeNull()
    expect(checkbox('项目').checked).toBe(true)
    expect(document.querySelector<HTMLButtonElement>('.picker-submit')!.disabled).toBe(true)
    await click('.picker-error button')
    expect(nav.layoutError).toBe('')
    expect(document.querySelector('.picker-error')).toBeNull()
    expect(checkbox('项目').checked).toBe(true)
    expect(document.querySelector<HTMLButtonElement>('.picker-submit')!.disabled).toBe(false)
  })
})
