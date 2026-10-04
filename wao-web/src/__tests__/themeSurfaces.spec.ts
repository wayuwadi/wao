import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

function source(relativePath: string) {
  return readFileSync(fileURLToPath(new URL(relativePath, import.meta.url)), 'utf8')
}

describe('夜间模式表面', () => {
  it('首页欢迎区与休息入口复用夜间主题表面', () => {
    const home = source('../views/HomeView.vue')

    expect(home).toMatch(
      /\.home-rest-welcome\s*\{[^}]*background: var\(--wao-bg-card\)/,
    )
    expect(home).toMatch(
      /\.rest-entrance\s*\{[^}]*background:\s*var\(--wao-bg-card\)/,
    )
  })

  it('独立视觉页面复用主题表面 token', () => {
    const marketLayout = source('../styles/capabilityResources.css')
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
