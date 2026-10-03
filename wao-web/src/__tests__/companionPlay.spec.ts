import { describe, expect, it } from 'vitest'
import { captureText, stepPlayBall } from '@/components/home/companionPlay'

describe('留白游乐场', () => {
  it('窄屏与高速投掷都留在底部区域，十五秒内自动收好', () => {
    for (const width of [280, 900]) {
      const ball = { x: 0, y: 100, vx: 900, vy: 1000, age: 0 }
      let visible = true
      for (let i = 0; i < 460; i++) {
        visible = stepPlayBall(ball, 1 / 30, width, 250)
        expect(Math.abs(ball.x)).toBeLessThanOrEqual(width / 2 - 11)
        expect(ball.y).toBeGreaterThanOrEqual(-114)
        expect(ball.y).toBeLessThanOrEqual(114)
      }
      expect(visible).toBe(false)
    }
  })
  it('文字接物保留文字和换行，拒绝空白及超长内容，不猜测链接或 HTML', () => {
    expect(captureText(' \r\n一段中文\r\n<script>原文</script>\r\n')).toBe('一段中文\n<script>原文</script>')
    expect(captureText('https://example.test/')).toBe('https://example.test/')
    expect(captureText('  ')).toBeNull()
    expect(captureText('字'.repeat(10001))).toBeNull()
    expect(captureText('字'.repeat(10000))).toHaveLength(10000)
  })
})
