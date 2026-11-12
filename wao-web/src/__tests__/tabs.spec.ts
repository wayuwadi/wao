import { beforeEach, describe, it, expect } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTabsStore } from '@/stores/tabs'
import type { Tab } from '@/types'

const proj = (id: string): Tab => ({
  key: `kb:${id}`,
  label: `知识库 ${id}`,
  icon: 'ri-stack-line',
  path: `/kb/${id}`,
  closable: true,
})

describe('tabs store', () => {
  it('项目合并到待办后移除旧项目页签及激活引用', () => {
    localStorage.setItem('wao-tabs-v1', JSON.stringify({ tabs: [
      {key:'projects',path:'/projects',label:'项目'},
      {key:'proj:p1',path:'/projects/p1',label:'旧项目'},
    ],activeKey:'proj:p1'}))
    setActivePinia(createPinia())
    const store=useTabsStore()
    expect(store.tabs.map(tab=>tab.key)).toEqual(['home'])
    expect(store.activeKey).toBe('home')
  })
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
    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'kb:p1'])
    expect(tabs.activeKey).toBe('home')
  })

  it('seeds a pinned home tab', () => {
    const tabs = useTabsStore()
    expect(tabs.tabs[0]?.key).toBe('home')
    expect(tabs.tabs[0]?.closable).toBe(false)
    expect(tabs.activeKey).toBe('home')
  })

  it('恢复历史页签时移除本次下线功能并保留知识库', () => {
    localStorage.setItem('wao-tabs-v1', JSON.stringify({ tabs: [
      ...['life-review', 'system-reports', 'ideas', 'focus', 'countdowns', 'tianren'].map(key => ({ key, path: '/' + key, label: '旧页签' })),
      proj('p1'),
    ], activeKey: 'life-review' }))
    const tabs = useTabsStore()
    expect(tabs.tabs.map(tab => tab.key)).toEqual(['home', 'kb:p1'])
    expect(tabs.activeKey).toBe('home')
  })

  it('opens, dedupes and activates tabs', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.open(proj('p1'))
    expect(tabs.tabs.map((t) => t.key)).toEqual(['home', 'kb:p1', 'kb:p2'])
    expect(tabs.activeKey).toBe('kb:p1')
  })

  it('activates a neighbour when closing the active tab', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.activate('kb:p1')
    const next = tabs.close('kb:p1')
    expect(next?.key).toBe('kb:p2')
    expect(tabs.activeKey).toBe('kb:p2')
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
    tabs.togglePin('kb:p1')
    tabs.open(proj('p2'))

    const next = tabs.closeAll()

    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'kb:p1'])
    expect(tabs.tabs.find((tab) => tab.key === 'kb:p1')?.pinned).toBe(true)
    expect(next?.key).toBe('kb:p1')
    expect(tabs.activeKey).toBe('kb:p1')
  })

  it('置顶状态跨刷新保留，取消置顶后可被批量关闭', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.togglePin('kb:p1')

    setActivePinia(createPinia())
    const restored = useTabsStore()
    expect(restored.tabs.find((tab) => tab.key === 'kb:p1')?.pinned).toBe(true)

    restored.togglePin('kb:p1')
    restored.closeAll()
    expect(restored.tabs.map((tab) => tab.key)).toEqual(['home'])
  })

  it('置顶后自动移到未置顶页签前，并支持本地重命名', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.open(proj('p3'))

    tabs.togglePin('kb:p3')
    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'kb:p3', 'kb:p1', 'kb:p2'])
    expect(tabs.rename('kb:p3', '常用知识库')).toBe(true)
    expect(tabs.tabs[1]?.label).toBe('常用知识库')
    tabs.open({ ...proj('p3'), label: '服务端知识库名' })
    expect(tabs.tabs[1]?.label).toBe('常用知识库')

    tabs.closeUnpinned()
    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'kb:p3'])
  })

  it('置顶只阻止批量关闭，成员仍可单独关闭该页签', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.togglePin('kb:p1')

    tabs.close('kb:p1')

    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home'])
  })

  it('closeOthers keeps home and the target', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.open(proj('p3'))
    tabs.closeOthers('kb:p2')
    expect(tabs.tabs.map((t) => t.key)).toEqual(['home', 'kb:p2'])
  })

  it('关闭其它、左边和右边时同样保留置顶页签', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.togglePin('kb:p1')
    tabs.open(proj('p2'))
    tabs.open(proj('p3'))

    tabs.closeOthers('kb:p3')
    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'kb:p1', 'kb:p3'])

    tabs.open(proj('p2'))
    tabs.closeLeft('kb:p2')
    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'kb:p1', 'kb:p2'])

    tabs.open(proj('p3'))
    tabs.togglePin('kb:p3')
    tabs.closeRight('kb:p1')
    expect(tabs.tabs.map((tab) => tab.key)).toEqual(['home', 'kb:p1', 'kb:p3'])
  })

  it('回填过真名后，占位标签不再覆盖它——知识库内切换标签页时标题会闪成编号', () => {
    const tabs = useTabsStore()
    // 首次进入：只有 id 拼出来的占位标签
    tabs.open({ ...proj('p3'), labelPlaceholder: true })
    expect(tabs.tabs.find((t) => t.key === 'kb:p3')?.label).toBe('知识库 p3')

    // 详情视图加载出实体后回填真名
    tabs.setLabel('kb:p3', '滇西北家庭旅行')

    // 知识库内切换 tab / view 会再次推导页签，此时不能被占位标签打回去
    tabs.open({ ...proj('p3'), labelPlaceholder: true, path: '/kb/p3?tab=任务' })

    const t = tabs.tabs.find((x) => x.key === 'kb:p3')
    expect(t?.label).toBe('滇西北家庭旅行')
    expect(t?.path).toBe('/kb/p3?tab=任务')
  })

  it('拖拽可以改页签顺序', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.open(proj('p3'))

    // 把最后一个拖到第一个业务页签的位置
    tabs.move('kb:p3', 'kb:p1')

    expect(tabs.tabs.map((t) => t.key)).toEqual(['home', 'kb:p3', 'kb:p1', 'kb:p2'])
  })

  it('首页钉在最左：既拖不走，别的页签也插不到它前面', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))

    tabs.move('home', 'kb:p2')
    expect(tabs.tabs.map((t) => t.key)).toEqual(['home', 'kb:p1', 'kb:p2'])

    tabs.move('kb:p2', 'home')
    expect(tabs.tabs.map((t) => t.key)).toEqual(['home', 'kb:p2', 'kb:p1'])
  })

  it('顺序持久化，刷新后还在', () => {
    const tabs = useTabsStore()
    tabs.open(proj('p1'))
    tabs.open(proj('p2'))
    tabs.move('kb:p2', 'kb:p1')

    setActivePinia(createPinia())
    expect(useTabsStore().tabs.map((t) => t.key)).toEqual(['home', 'kb:p2', 'kb:p1'])
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
    tabs.setLabel('kb:p3', '旧名字')

    tabs.open({ ...proj('p3'), label: '新名字' })

    expect(tabs.tabs.find((t) => t.key === 'kb:p3')?.label).toBe('新名字')
  })
})


describe('持久页签名称迁移', () => {
  it('恢复所有模块默认标题，保留自定义和实体名、路径及置顶', () => {
    localStorage.setItem('wao-tabs-v1', JSON.stringify({ activeKey: 'navigation', tabs: [
      { key: 'habits', label: '习惯打卡', path: '/habits', icon: 'icon', closable: true },
      { key: 'navigation', label: '工作台', path: '/navigation?section=tools', icon: 'icon', closable: true, pinned: true },
      { key: 'workspaces', label: '链接空间', path: '/workspaces', icon: 'icon', closable: true },
      { key: 'ai-sessions', label: 'AI 会话', path: '/ai-sessions', icon: 'icon', closable: true },
      { key: 'settings', label: '我的设置', path: '/settings', icon: 'icon', closable: true, customLabel: true },
      { key: 'kb:p1', label: '真实知识库', path: '/kb/p1', icon: 'icon', closable: true },
    ] }))
    setActivePinia(createPinia())
    const store = useTabsStore()
    expect(store.tabs.find(t => t.key === 'navigation')).toMatchObject({ label: '全部', path: '/navigation?section=tools', pinned: true })
    expect(store.tabs.some(t => t.key === 'workspaces' || t.key === 'ai-sessions')).toBe(false)
    expect(store.tabs.find(t => t.key === 'settings')?.label).toBe('我的设置')
    expect(store.tabs.find(t => t.key === 'kb:p1')?.label).toBe('真实知识库')
    expect(store.tabs.find(t => t.key === 'habits')?.label).toBe('习惯')
    expect(store.activeKey).toBe('navigation')
    store.activate('settings')
    setActivePinia(createPinia())
    expect(useTabsStore().tabs.find(t => t.key === 'navigation')?.label).toBe('全部')
  })
})
