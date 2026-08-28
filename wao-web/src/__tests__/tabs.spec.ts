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

  it('拖拽可以改页签顺序', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.open(proj('p3'))

    // 把最后一个拖到第一个业务页签的位置
    tabs.move('proj:p3', 'proj:p1')

    expect(tabs.tabs.map((t) => t.key)).toEqual(['home', 'proj:p3', 'proj:p1', 'proj:p2'])
  })

  it('首页钉在最左：既拖不走，别的页签也插不到它前面', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))

    tabs.move('home', 'proj:p2')
    expect(tabs.tabs.map((t) => t.key)).toEqual(['home', 'proj:p1', 'proj:p2'])

    tabs.move('proj:p2', 'home')
    expect(tabs.tabs.map((t) => t.key)).toEqual(['home', 'proj:p2', 'proj:p1'])
  })

  it('顺序持久化，刷新后还在', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.move('proj:p2', 'proj:p1')

    setActivePinia(createPinia())
    expect(useTabsStore().tabs.map((t) => t.key)).toEqual(['home', 'proj:p2', 'proj:p1'])
  })

  it('回收站下线后，老浏览器里存着的页签要被丢掉，不能留下点不开的死页签', () => {
    localStorage.setItem(
      'wao-tabs-v1',
      JSON.stringify({
        tabs: [
          { key: 'home', label: '首页', icon: 'ri-home-5-line', path: '/', closable: false },
          {
            key: 'trash',
            label: '回收站',
            icon: 'ri-delete-bin-6-line',
            path: '/trash',
            closable: true,
          },
        ],
        activeKey: 'trash',
      }),
    )

    const tabs = useTabsStore()

    expect(tabs.tabs.map((t) => t.key)).toEqual(['home'])
    expect(tabs.activeKey).toBe('home')
  })

  it('聊天改成悬浮框后清理老浏览器里的聊天页签', () => {
    localStorage.setItem(
      'wao-tabs-v1',
      JSON.stringify({
        tabs: [
          { key: 'home', label: '首页', icon: 'ri-home-5-line', path: '/', closable: false },
          { key: 'chat', label: '聊天', icon: 'ri-chat-3-line', path: '/chat', closable: true },
        ],
        activeKey: 'chat',
      }),
    )

    const tabs = useTabsStore()

    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home'])
    expect(tabs.activeKey).toBe('home')
  })

  it('非占位标签仍然可以更新（真名变了要跟着变）', () => {
    const tabs = useTabsStore()
    tabs.open({ ...proj('p3'), labelPlaceholder: true })
    tabs.setLabel('proj:p3', '旧名字')

    tabs.open({ ...proj('p3'), label: '新名字' })

    expect(tabs.tabs.find((t) => t.key === 'proj:p3')?.label).toBe('新名字')
  })
})
