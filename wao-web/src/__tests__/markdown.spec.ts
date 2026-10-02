import { describe, expect, it } from 'vitest'
import { renderChatReviewMarkdown, renderMarkdown } from '@/utils/markdown'

describe('renderMarkdown', () => {
  it('渲染标题、列表与行内样式', () => {
    const html = renderMarkdown('## 目标\n\n- 第一项\n- 第二项\n\n**重点**与 `代码`')
    expect(html).toContain('<h2>目标</h2>')
    expect(html).toContain('<li>第一项</li>')
    expect(html).toContain('<strong>重点</strong>')
    expect(html).toContain('<code>代码</code>')
  })

  it('单个换行即断行，用户敲一次回车就换行', () => {
    expect(renderMarkdown('第一行\n第二行')).toContain('<br>')
  })

  it('剥掉 script 等危险标签，避免描述内容变成 XSS 入口', () => {
    const html = renderMarkdown('正常文本 <script>alert(1)</script><img src=x onerror="alert(1)">')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('onerror')
    expect(html).toContain('正常文本')
  })

  it('外链补上 target 与 noopener', () => {
    const html = renderMarkdown('[站点](https://example.com)')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noopener noreferrer"')
  })

  it('只把严格白名单内的图片尺寸元数据转换成安全样式', () => {
    const percent = renderMarkdown('![截图](/api/images/img1/raw "wao-image-size:50%xauto")')
    const pixels = renderMarkdown('![截图](/api/images/img1/raw "wao-image-size:640x480")')

    expect(percent).toContain('style="width:50%;height:auto"')
    expect(percent).not.toContain('wao-image-size:')
    expect(pixels).toContain(
      'style="width:640px;aspect-ratio:640/480;height:auto;object-fit:contain"',
    )
  })

  it('拒绝越界尺寸和 Markdown 原始任意 style', () => {
    const invalidMetadata = renderMarkdown(
      '![越界](/api/images/img1/raw "wao-image-size:9999xauto")',
    )
    const rawStyle = renderMarkdown(
      '<img src="/api/images/img1/raw" style="position:fixed;inset:0" alt="原始样式">',
    )

    expect(invalidMetadata).not.toContain('style=')
    expect(invalidMetadata).not.toContain('wao-image-size:')
    expect(rawStyle).not.toContain('style=')
  })

  it('图片缩进仅接受 1–8 级，兼容无尺寸图片和标题', () => {
    const html = renderMarkdown('![图](/api/images/test/raw "wao-image-size:autoxauto;indent=2;title=hello")')
    expect(html).toContain('margin-left:min(48px,50%)')
    expect(html).toContain('max-width:calc(100% - min(48px,50%))')
    expect(html).toContain('title="hello"')
    expect(html).not.toContain('nullpx')
    for (const invalid of ['0', '9', '-1', '1.5', '1;position=fixed']) {
      expect(renderMarkdown(`![图](/api/images/test/raw "wao-image-size:autoxauto;indent=${invalid}")`)).not.toContain('style=')
    }
  })

  it('空内容返回空串', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown('   ')).toBe('')
  })

  it('聊天复盘专用渲染保留列表样式但移除所有外联资源', () => {
    const html = renderChatReviewMarkdown(`
- **结论** [S1]
- [追踪链接](https://evil.example/collect)
- ![追踪图](https://evil.example/pixel.png)
<img src="https://evil.example/raw.png">
`)

    expect(html).toContain('<ul>')
    expect(html).toContain('<strong>结论</strong>')
    expect(html).not.toContain('<a')
    expect(html).not.toContain('<img')
    expect(html).not.toContain('href=')
    expect(html).not.toContain('src=')
  })
})
