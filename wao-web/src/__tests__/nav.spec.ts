import { describe, it, expect } from 'vitest'
import { MODULES, NAV_GROUPS } from '@/config/nav'
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

  it('自媒体 Agent 作为独立应用入口出现在工具组并拥有受保护路由', () => {
    const tools = NAV_GROUPS.find((group) => group.label === '工具')
    expect(tools?.items).toContain('mediaAgent')
    expect(MODULES.mediaAgent.path).toBe('/media-agent')
    const route = router.getRoutes().find((item) => item.name === 'media-agent')
    expect(route?.meta.module).toBe('mediaAgent')
    expect(route?.meta.public).not.toBe(true)
  })
})
