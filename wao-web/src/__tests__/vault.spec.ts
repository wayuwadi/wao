import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { apiCreateVaultItem, apiPatchVaultItem } from '@/api/board'
import VaultView from '@/views/VaultView.vue'
import type { VaultGroup, VaultItem } from '@/types'

/**
 * 密码库：读失败时不能拿演示数据顶上，默认分组按后端标记认而不是按名字认。
 *
 * 线上出现过一次「密码库一直显示演示数据」：后端默认分组被改名后 GET /api/vault 每次 409，
 * 前端静默退回 fixtures，看起来像「我的密码变成了别人的条目」（见 ADR-036）。
 */

const ok = vi.fn<() => Promise<never[]>>(async () => [])
const fetchVault = vi.fn<() => Promise<VaultGroup[]>>()
const revealApi = vi.fn<(id: string) => Promise<{ secret: string | null }>>()
const copySpy = vi.fn<(text: string, msg?: string) => void>()

vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>()
  return {
    ...actual,
    fetchAreas: () => ok(), fetchProjects: () => ok(), fetchTasks: () => ok(),
    fetchKbs: () => ok(), fetchKbGroups: () => ok(), fetchDocs: () => ok(),
    fetchJobs: () => ok(), fetchFiles: () => ok(), fetchImages: () => ok(),
    fetchSchedule: () => ok(), fetchSuggestions: () => ok(), fetchGoals: () => ok(),
    fetchVault: () => fetchVault(),
    apiCreateVaultItem: vi.fn(),
    apiPatchVaultItem: vi.fn(),
    apiRevealVaultItem: (id: string) => revealApi(id),
  }
})

vi.mock('@/utils/clipboard', () => ({
  copyToClipboard: async () => true,
  copyText: (text: string, msg?: string) => copySpy(text, msg),
}))

const group = (over: Partial<VaultGroup> = {}): VaultGroup => ({
  id: 'vg-default-wg',
  group: 'Amer',
  isDefault: true,
  items: [],
  ...over,
})

const item = (over: Partial<VaultItem> = {}): VaultItem => ({
  id: 'vi1',
  name: '家庭 Wi-Fi',
  icon: 'ri-wifi-line',
  user: 'wadi',
  site: 'router.local',
  note: '',
  updated: '2026-07-30',
  hasSecret: true,
  ...over,
})

describe('密码库读取失败', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    fetchVault.mockReset()
  })

  it('后端在线但密码库读不出来时，清空列表并报错，不留演示凭据', async () => {
    fetchVault.mockRejectedValue(new Error('409'))
    const data = useDataStore()

    await data.hydrate()

    expect(data.apiLive).toBe(true)
    expect(data.vault).toEqual([])
    expect(data.vaultError).toBe(true)
  })

  it('读到真实数据时不报错，也不再混入演示数据', async () => {
    fetchVault.mockResolvedValue([group()])
    const data = useDataStore()

    await data.hydrate()

    expect(data.vaultError).toBe(false)
    expect(data.vault.map((g) => g.id)).toEqual(['vg-default-wg'])
  })

  it('重试成功后横幅消失', async () => {
    fetchVault.mockRejectedValueOnce(new Error('409')).mockResolvedValueOnce([group()])
    const data = useDataStore()
    await data.hydrate()

    await expect(data.reloadVault()).resolves.toBe(true)

    expect(data.vaultError).toBe(false)
    expect(data.vault).toHaveLength(1)
  })
})

describe('默认分组', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    fetchVault.mockReset()
  })

  function mountVault() {
    return mount(VaultView, { global: { stubs: { DemoBanner: true } } })
  }

  it('改过名的默认分组仍然不给删', async () => {
    const data = useDataStore()
    data.vault = [group()]
    const view = mountVault()

    await view.find('.group-manage').trigger('click')

    const del = view.findAll('.group-action.danger')
    expect(del).toHaveLength(1)
    expect(del[0]!.attributes('disabled')).toBeDefined()
  })

  it('名叫「未分组」的普通分组不再被当成默认分组', async () => {
    const data = useDataStore()
    data.vault = [group({ id: 'vg9', group: '未分组', isDefault: false })]
    const view = mountVault()

    await view.find('.group-manage').trigger('click')

    expect(view.findAll('.group-action.danger')[0]!.attributes('disabled')).toBeUndefined()
  })
})

/**
 * 手机端查看 / 复制密码。
 *
 * 密码库原来是固定宽度的三栏（分组 158px + 条目 252px + 详情），手机上前两栏就占满了屏幕，
 * 唯一放着「查看」「复制」的详情栏被挤出可视区——用户看到的就是「手机上没地方看密码」。
 * 手机改成列表 → 详情盖屏页 → 返回。
 */
describe('手机端查看 / 复制密码', () => {
  const desktopMatchMedia = window.matchMedia

  beforeEach(() => {
    setActivePinia(createPinia())
    fetchVault.mockReset()
    revealApi.mockReset()
    copySpy.mockReset()
    window.matchMedia = ((query: string) => ({
      matches: true, media: query, onchange: null,
      addEventListener: () => {}, removeEventListener: () => {},
      addListener: () => {}, removeListener: () => {}, dispatchEvent: () => false,
    })) as unknown as typeof window.matchMedia
  })

  afterEach(() => {
    window.matchMedia = desktopMatchMedia
  })

  function mountMobile() {
    const data = useDataStore()
    data.apiLive = true
    data.vault = [group({ items: [item(), item({ id: 'vi2', name: '路由器后台', user: 'admin' })] })]
    return mount(VaultView, { global: { stubs: { DemoBanner: true } } })
  }

  it('手机上先看列表，不默认盖着第一条的详情页', () => {
    const view = mountMobile()

    expect(view.findAll('.item-btn')).toHaveLength(2)
    expect(view.find('.detail-page').exists()).toBe(false)

    view.unmount()
  })

  it('点条目进详情页，能查看和复制密码，返回退回列表', async () => {
    revealApi.mockResolvedValue({ secret: 'hunter2' })
    const view = mountMobile()

    await view.findAll('.item-btn')[1]!.trigger('click')
    const page = view.find('.detail-page')
    expect(page.exists()).toBe(true)
    expect(page.find('.dp-title').text()).toBe('路由器后台')

    await page.find('.field-row .f-icon').trigger('click')
    await flushPromises()
    expect(revealApi).toHaveBeenCalledWith('vi2')
    expect(page.find('.f-pass').text()).toBe('hunter2')

    await page.findAll('.field-row .f-btn')[1]!.trigger('click')
    await flushPromises()
    expect(copySpy).toHaveBeenCalledWith('hunter2', '已复制密码')

    await page.find('.dp-back').trigger('click')
    expect(view.find('.detail-page').exists()).toBe(false)

    view.unmount()
  })

  it('没点开过「查看」也能直接复制：复制时按需解密一次', async () => {
    revealApi.mockResolvedValue({ secret: 's3cret' })
    const view = mountMobile()

    await view.findAll('.item-btn')[0]!.trigger('click')
    await view.findAll('.detail-page .field-row .f-btn')[1]!.trigger('click')
    await flushPromises()

    expect(copySpy).toHaveBeenCalledWith('s3cret', '已复制密码')

    view.unmount()
  })

  it('桌面仍是三栏并排，详情默认落在第一条上', () => {
    window.matchMedia = desktopMatchMedia
    const data = useDataStore()
    data.vault = [group({ items: [item()] })]
    const view = mount(VaultView, { global: { stubs: { DemoBanner: true } } })

    expect(view.find('.detail-page').exists()).toBe(false)
    expect(view.find('.detail-col').exists()).toBe(true)
    expect(view.find('.d-name').text()).toBe('家庭 Wi-Fi')

    view.unmount()
  })
})


describe('密码本快速录入', () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.mocked(apiCreateVaultItem).mockReset(); vi.mocked(apiPatchVaultItem).mockReset() })
  it('搜索结果跨组编辑后继续选中编辑的条目', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.vault = [group({ items: [item()] }), group({ id: 'work', group: '工作', isDefault: false, items: [item({ id: 'other', name: '其他条目' })] })]
    vi.mocked(apiPatchVaultItem).mockResolvedValue(item())
    const view = mount(VaultView, { global: { stubs: { DemoBanner: true } } })
    await view.get('.vault-search').setValue('家庭')
    await view.get('button[title="编辑"]').trigger('click')
    await view.findAll('.group-choices button')[1]!.trigger('click')
    await view.get('.modal-foot .btn-primary').trigger('click')
    await flushPromises()
    expect(view.get('.d-name').text()).toBe('家庭 Wi-Fi')
    expect(view.findAll('.item-btn')).toHaveLength(2)
    view.unmount()
  })
  it('切换搜索结果后不展示前一条目的迟到密码响应', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.vault = [group({ items: [item(), item({ id: 'vi2', name: '其他条目' })] })]
    let finish!: (value: { secret: string }) => void
    revealApi.mockReturnValueOnce(new Promise((resolve) => { finish = resolve }))
    const view = mount(VaultView, { global: { stubs: { DemoBanner: true } } })
    await view.get('button[title="查看"]').trigger('click')
    await view.get('.vault-search').setValue('其他')
    finish({ secret: 'test-only-secret' })
    await flushPromises()
    expect(view.get('.d-name').text()).toBe('其他条目')
    expect(view.text()).not.toContain('test-only-secret')
    view.unmount()
  })
  it('在当前分组新建，保存失败保留输入，成功后展示新条目', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.vault = [group(), group({ id: 'work', group: '工作', isDefault: false })]
    vi.mocked(apiCreateVaultItem).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(item({ id: 'new', name: '验收条目' }))
    const wrapper = mount(VaultView, { global: { stubs: { DemoBanner: true } } })
    await wrapper.findAll('.group-btn')[2]!.trigger('click')
    await wrapper.get('.vtop .btn-primary').trigger('click')
    expect(wrapper.findAll('.group-choices button')[1]!.attributes('aria-pressed')).toBe('true')
    await wrapper.get('input[placeholder="例如「家庭 Wi-Fi」"]').setValue('验收条目')
    await wrapper.get('.modal-foot .btn-primary').trigger('click')
    await flushPromises()
    expect(wrapper.find('.modal').exists()).toBe(true)
    expect(wrapper.get('[role="alert"]').text()).toContain('输入已保留')
    expect(data.vault[1]!.items).toEqual([])
    await wrapper.get('.modal-foot .btn-primary').trigger('click')
    await flushPromises()
    expect(apiCreateVaultItem).toHaveBeenLastCalledWith(expect.objectContaining({ groupId: 'work', name: '验收条目' }))
    expect(wrapper.find('.modal').exists()).toBe(false)
    expect(wrapper.text()).toContain('验收条目')
    wrapper.unmount()
  })
  it('跨组编辑失败不改变原条目，成功后只出现在目标组', async () => {
    const data = useDataStore()
    data.apiLive = true
    data.vault = [group({ items: [item()] }), group({ id: 'work', group: '工作', isDefault: false })]
    vi.mocked(apiPatchVaultItem).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(item({ name: '新名称' }))
    expect(await data.updateVaultItem('vi1', { groupId: 'work', name: '新名称' })).toBe(false)
    expect(data.vault[0]!.items[0]!.name).toBe('家庭 Wi-Fi')
    expect(await data.updateVaultItem('vi1', { groupId: 'work', name: '新名称' })).toBe(true)
    expect(data.vault[0]!.items).toEqual([])
    expect(data.vault[1]!.items[0]!.name).toBe('新名称')
  })
})
