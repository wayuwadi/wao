import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import VaultView from '@/views/VaultView.vue'
import type { VaultGroup } from '@/types'

/**
 * 密码库：读失败时不能拿演示数据顶上，默认分组按后端标记认而不是按名字认。
 *
 * 线上出现过一次「密码库一直显示演示数据」：后端默认分组被改名后 GET /api/vault 每次 409，
 * 前端静默退回 fixtures，看起来像「我的密码变成了别人的条目」（见 ADR-036）。
 */

const ok = vi.fn(async () => [] as never[])
const fetchVault = vi.fn()

vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>()
  return {
    ...actual,
    fetchAreas: () => ok(), fetchProjects: () => ok(), fetchTasks: () => ok(),
    fetchKbs: () => ok(), fetchKbGroups: () => ok(), fetchDocs: () => ok(),
    fetchJobs: () => ok(), fetchFiles: () => ok(), fetchImages: () => ok(),
    fetchSchedule: () => ok(), fetchSuggestions: () => ok(), fetchGoals: () => ok(),
    fetchVault: () => fetchVault(),
  }
})

const group = (over: Partial<VaultGroup> = {}): VaultGroup => ({
  id: 'vg-default-wg',
  group: 'Amer',
  isDefault: true,
  items: [],
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
