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

  it('丢弃已退役 Agent 的新旧历史页签', () => {
    localStorage.setItem('wao-tabs-v1', JSON.stringify({tabs: [
      { key: 'mediaAgent', path: '/media-agent', label: '旧助手' },
      { key: 'external:media-agent', path: '/apps/media-agent', label: '助手' },
      proj('p1'),
    ], activeKey: 'external:media-agent'}))
    const tabs = useTabsStore()
    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'proj:p1'])
    expect(tabs.activeKey).toBe('home')
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

  it('关闭全部时保留成员置顶页签，并跳转到最后一个保留项', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.togglePin('proj:p1')
    tabs.open(proj('p2'))

    const next = tabs.closeAll()

    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'proj:p1'])
    expect(tabs.tabs.find((tab) => tab.key === 'proj:p1')?.pinned).toBe(true)
    expect(next?.key).toBe('proj:p1')
    expect(tabs.activeKey).toBe('proj:p1')
  })

  it('置顶状态跨刷新保留，取消置顶后可被批量关闭', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.togglePin('proj:p1')

    setActivePinia(createPinia())
    const restored = useTabsStore()
    expect(restored.tabs.find((tab) => tab.key === 'proj:p1')?.pinned).toBe(true)

    restored.togglePin('proj:p1')
    restored.closeAll()
    expect(restored.tabs.map((tab) => tab.key)).toEqual(['home'])
  })

  it('置顶后自动移到未置顶页签前，并支持本地重命名', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.open(proj('p3'))

    tabs.togglePin('proj:p3')
    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'proj:p3', 'proj:p1', 'proj:p2'])
    expect(tabs.rename('proj:p3', '常用项目')).toBe(true)
    expect(tabs.tabs[1]?.label).toBe('常用项目')
    tabs.open({ ...proj('p3'), label: '服务端项目名' })
    expect(tabs.tabs[1]?.label).toBe('常用项目')

    tabs.closeUnpinned()
    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'proj:p3'])
  })

  it('置顶只阻止批量关闭，成员仍可单独关闭该页签', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.togglePin('proj:p1')

    tabs.close('proj:p1')

    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home'])
  })

  it('closeOthers keeps home and the target', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.open(proj('p3'))
    tabs.closeOthers('proj:p2')
    expect(tabs.tabs.map((t) => t.key)).toEqual(['home', 'proj:p2'])
  })

  it('关闭其它、左边和右边时同样保留置顶页签', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.togglePin('proj:p1')
    tabs.open(proj('p2'))
    tabs.open(proj('p3'))

    tabs.closeOthers('proj:p3')
    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'proj:p1', 'proj:p3'])

    tabs.open(proj('p2'))
    tabs.closeLeft('proj:p2')
    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'proj:p1', 'proj:p2'])

    tabs.open(proj('p3'))
    tabs.togglePin('proj:p3')
    tabs.closeRight('proj:p1')
    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'proj:p1', 'proj:p3'])
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


describe('持久页签名称迁移', () => {
  it('恢复所有模块默认标题，保留自定义和实体名、路径及置顶', () => {
    localStorage.setItem('wao-tabs-v1', JSON.stringify({ activeKey: 'navigation', tabs: [
      { key: 'navigation', label: '工作台', path: '/navigation?section=tools', icon: 'icon', closable: true, pinned: true },
      { key: 'workspaces', label: '链接空间', path: '/workspaces', icon: 'icon', closable: true },
      { key: 'ai-sessions', label: 'AI 会话', path: '/ai-sessions', icon: 'icon', closable: true },
      { key: 'settings', label: '我的设置', path: '/settings', icon: 'icon', closable: true, customLabel: true },
      { key: 'proj:p1', label: '真实项目', path: '/projects/p1', icon: 'icon', closable: true },
    ] }))
    setActivePinia(createPinia())
    const store = useTabsStore()
    expect(store.tabs.find(t => t.key === 'navigation')).toMatchObject({ label: '全部功能', path: '/navigation?section=tools', pinned: true })
    expect(store.tabs.some(t => t.key === 'workspaces' || t.key === 'ai-sessions')).toBe(false)
    expect(store.tabs.find(t => t.key === 'settings')?.label).toBe('我的设置')
    expect(store.tabs.find(t => t.key === 'proj:p1')?.label).toBe('真实项目')
    expect(store.activeKey).toBe('navigation')
    store.activate('settings')
    setActivePinia(createPinia())
    expect(useTabsStore().tabs.find(t => t.key === 'navigation')?.label).toBe('全部功能')
  })
})
