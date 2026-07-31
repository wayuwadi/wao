import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

// 这是一份直接发链接给收礼物的人的静态页，不进 SPA 路由也就没有组件测试兜底，
// 这里守住几条一旦破坏就等于页面废掉的约束。
const html = readFileSync(resolve(process.cwd(), 'public/birthday/gonghui.html'), 'utf-8')

describe('生日祝福静态页', () => {
  it('保留主人公、生日和 30 朵玫瑰的核心文案', () => {
    expect(html).toContain('龚慧')
    expect(html).toContain('8 · 1 生日快乐')
    expect(html).toContain('30 朵永不凋零的夏天玫瑰')
    expect(html).toMatch(/var ROSE_COUNT = 30;/)
  })

  it('花束按 30 朵布局，三圈数量之和不被改坏', () => {
    const counts = [...html.matchAll(/\{ n: (\d+), rf: /g)].map((m) => Number(m[1]))
    expect(counts).toHaveLength(3)
    expect(counts.reduce((sum, n) => sum + n, 0)).toBe(30)
  })

  it('手机端可用：视口铺满刘海屏，并申请陀螺仪权限做悬空视差', () => {
    expect(html).toContain('viewport-fit=cover')
    expect(html).toContain('DeviceOrientationEvent')
    expect(html).toContain('requestPermission')
    expect(html).toContain('deviceorientation')
  })

  it('尊重系统的减少动态效果设置', () => {
    expect(html).toContain('prefers-reduced-motion')
  })

  it('不依赖任何外部资源，断网也能打开', () => {
    const externals = [...html.matchAll(/(?:src|href)="(https?:)?\/\/[^"]+"/g)]
    expect(externals).toHaveLength(0)
  })
})
