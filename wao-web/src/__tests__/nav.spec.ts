import { describe, it, expect } from 'vitest'
import {
  DEFAULT_MEDIA_AGENT_MENU,
  MODULES,
  NAV_GROUPS,
  PERSONAL_ITEMS,
  resolveNavGroups,
} from '@/config/nav'
import router from '@/router'

describe('侧边栏导航配置', () => {
  it('每个分组里的模块都能在 MODULES 里查到（侧边栏渲染直接吃这份配置）', () => {
    for (const g of NAV_GROUPS) {
      for (const id of g.items) {
        expect(MODULES[id], `分组「${g.label || '首页'}」里的 ${id} 缺少模块元数据`).toBeTruthy()
      }
    }
  })

  it('回收站已下线：侧边栏不再有这个菜单，路由也一并撤掉', () => {
    const ids = NAV_GROUPS.flatMap((g) => g.items)
    expect(ids).not.toContain('trash')
    expect(Object.keys(MODULES)).not.toContain('trash')
    expect(router.getRoutes().some((r) => r.name === 'trash')).toBe(false)
  })

  it('回收站入口仍留在知识库页内，不随菜单一起消失', () => {
    // 软删除与 7 天保留期（ADR-018）没有下线，只是不再占一个一级菜单。
    expect(router.getRoutes().some((r) => r.name === 'knowledge')).toBe(true)
  })

  it('自媒体 Agent 默认出现在公共组并拥有受保护路由', () => {
    const publicGroup = NAV_GROUPS.find((group) => group.label === '公共')
    expect(publicGroup?.items).toEqual(['projects', 'mediaAgent'])
    expect(MODULES.mediaAgent.path).toBe('/media-agent')
    const route = router.getRoutes().find((item) => item.name === 'media-agent')
    expect(route?.meta.module).toBe('mediaAgent')
    expect(route?.meta.public).not.toBe(true)
  })

  it('按全局配置移动、排序或隐藏 Agent 菜单，不改变个人模块顺序', () => {
    const tools = resolveNavGroups(PERSONAL_ITEMS, {
      ...DEFAULT_MEDIA_AGENT_MENU,
      group: 'TOOLS',
      order: 0,
      label: '内容助手',
    })
    expect(tools.find((group) => group.label === '工具')?.items).toEqual(['mediaAgent', 'automation', 'files'])

    const personalOrder = [...PERSONAL_ITEMS].reverse()
    const personal = resolveNavGroups(personalOrder, {
      ...DEFAULT_MEDIA_AGENT_MENU,
      group: 'PERSONAL',
      order: 15,
    })
    expect(personal.find((group) => group.label === '个人')?.items).toEqual([
      personalOrder[0],
      'mediaAgent',
      ...personalOrder.slice(1),
    ])

    const hidden = resolveNavGroups(personalOrder, {
      ...DEFAULT_MEDIA_AGENT_MENU,
      visible: false,
    })
    expect(hidden.flatMap((group) => group.items)).not.toContain('mediaAgent')
    expect(hidden.find((group) => group.label === '个人')?.items).toEqual(personalOrder)
  })
})
