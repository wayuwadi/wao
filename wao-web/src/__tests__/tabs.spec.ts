import { beforeEach, describe, it, expect } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTabsStore } from '@/stores/tabs'
import type { Tab } from '@/types'

const proj = (id: string): Tab => ({
  key: `proj:${id}`,
  label: `项目 ${id}`,
  icon: 'ri-stack-line',
  path: `/projects/${id}`,
  closable: true,
})

describe('tabs store', () => {
  beforeEach(() => {
    localStorage.clear()
    setActivePinia(createPinia())
  })

  it('seeds a pinned home tab', () => {
    const tabs = useTabsStore()
    expect(tabs.tabs[0]?.key).toBe('home')
    expect(tabs.tabs[0]?.closable).toBe(false)
    expect(tabs.activeKey).toBe('home')
  })

  it('opens, dedupes and activates tabs', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.open(proj('p1'))
    expect(tabs.tabs.map((t) => t.key)).toEqual(['home', 'proj:p1', 'proj:p2'])
    expect(tabs.activeKey).toBe('proj:p1')
  })

  it('activates a neighbour when closing the active tab', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.activate('proj:p1')
    const next = tabs.close('proj:p1')
    expect(next?.key).toBe('proj:p2')
    expect(tabs.activeKey).toBe('proj:p2')
  })

  it('never closes the pinned home tab', () => {
    const tabs = useTabsStore()
    expect(tabs.close('home')).toBeNull()
    tabs.open(proj('p1'))
    tabs.closeAll()
    expect(tabs.tabs.map((t) => t.key)).toEqual(['home'])
    expect(tabs.activeKey).toBe('home')
  })

  it('closeOthers keeps home and the target', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.open(proj('p3'))
    tabs.closeOthers('proj:p2')
    expect(tabs.tabs.map((t) => t.key)).toEqual(['home', 'proj:p2'])
  })

  it('回填过真名后，占位标签不再覆盖它——项目内切换标签页时标题会闪成编号', () => {
    const tabs = useTabsStore()
    // 首次进入：只有 id 拼出来的占位标签
    tabs.open({ ...proj('p3'), labelPlaceholder: true })
    expect(tabs.tabs.find((t) => t.key === 'proj:p3')?.label).toBe('项目 p3')

    // 详情视图加载出实体后回填真名
    tabs.setLabel('proj:p3', '滇西北家庭旅行')

    // 项目内切换 tab / view 会再次推导页签，此时不能被占位标签打回去
    tabs.open({ ...proj('p3'), labelPlaceholder: true, path: '/projects/p3?tab=任务' })

    const t = tabs.tabs.find((x) => x.key === 'proj:p3')
    expect(t?.label).toBe('滇西北家庭旅行')
    expect(t?.path).toBe('/projects/p3?tab=任务')
  })

  it('非占位标签仍然可以更新（真名变了要跟着变）', () => {
    const tabs = useTabsStore()
    tabs.open({ ...proj('p3'), labelPlaceholder: true })
    tabs.setLabel('proj:p3', '旧名字')

    tabs.open({ ...proj('p3'), label: '新名字' })

    expect(tabs.tabs.find((t) => t.key === 'proj:p3')?.label).toBe('新名字')
  })
})
