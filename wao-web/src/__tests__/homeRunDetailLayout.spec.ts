import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const homeSource = readFileSync(resolve(process.cwd(), 'src/views/HomeView.vue'), 'utf8')

describe('AI 运行详情布局', () => {
  it('十项运行元数据在宽屏排成五列两行，手机回落为单列', () => {
    expect(homeSource).toContain('<dl class="run-detail-meta">')
    expect(homeSource).toMatch(
      /\.run-activity-detail \.run-detail-meta \{ grid-template-columns: repeat\(5, minmax\(0, 1fr\)\); \}/,
    )
    expect(homeSource).toMatch(
      /@media \(max-width: 760px\)[\s\S]*?\.run-detail dl, \.run-activity-detail \.run-detail-meta \{ grid-template-columns: 1fr; \}/,
    )
  })
})
