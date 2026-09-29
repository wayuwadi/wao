import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

function source(relativePath: string) {
  return readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8')
}

describe('夜间模式表面', () => {
  it('首页首屏和自动推进策略不再固定为浅色背景', () => {
    const home = source('../views/HomeView.vue')

    expect(home).toMatch(
      /\.goal-launcher[\s\S]*?background: linear-gradient\([^;]*var\(--wao-bg-card\)/,
    )
    expect(home).toMatch(
      /\.auto-run-policy[\s\S]*?background: linear-gradient\([^;]*var\(--wao-bg-card\)/,
    )
    expect(home).not.toContain(
      "queued: { label: '等待电脑领取', icon: 'ri-time-line', color: '#8a5b0d'",
    )
  })

  it('独立视觉页面复用主题表面 token', () => {
    const marketLayout = source('../layouts/SkillMarketLayout.vue')
    const desktopDownload = source('../views/DesktopDownloadView.vue')

    expect(marketLayout).toContain(":root[data-theme='dark']")
    expect(marketLayout).toMatch(/\.market-card\{background:var\(--market-card\)/)
    expect(desktopDownload).toMatch(
      /\.download-grid article \{[^}]*background: var\(--wao-bg-card\)/,
    )
  })

  it('画板工具随主题切换且不改写用户画布背景', () => {
    const canvas = source('../components/drawings/DrawingCanvas.vue')

    expect(canvas).toContain('theme: effective.value')
    expect(canvas).toContain(
      "viewBackgroundColor: scene.appState?.viewBackgroundColor ?? '#ffffff'",
    )
  })
})
