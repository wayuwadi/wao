import { describe, expect, it } from 'vitest'
import { applyMarkdownCommand, renderMarkdown, stripMarkdown } from '@/utils/markdown'

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

  it('空内容返回空串', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown('   ')).toBe('')
  })
})

describe('stripMarkdown', () => {
  it('还原成一行纯文本', () => {
    expect(stripMarkdown('## 标题\n- 条目 **粗体**\n[链接](https://x)')).toBe('标题 条目 粗体 链接')
  })

  it('图片折成占位符', () => {
    expect(stripMarkdown('看这张 ![图](https://x/y.png)')).toBe('看这张 [图片]')
  })
})

describe('applyMarkdownCommand', () => {
  it('行级命令加在当前行首', () => {
    const out = applyMarkdownCommand('待办事项', 2, 2, 'ul')
    expect(out.text).toBe('- 待办事项')
    expect(out.start).toBe(4)
  })

  it('再点一次同一个行级命令则取消', () => {
    const out = applyMarkdownCommand('- 待办事项', 4, 4, 'ul')
    expect(out.text).toBe('待办事项')
    expect(out.start).toBe(2)
  })

  it('行级命令只作用于光标所在行', () => {
    const text = '第一行\n第二行'
    const out = applyMarkdownCommand(text, 5, 5, 'h2')
    expect(out.text).toBe('第一行\n## 第二行')
  })

  it('行内命令包裹选区并保持选中', () => {
    const out = applyMarkdownCommand('要加粗的字', 0, 3, 'bold')
    expect(out.text).toBe('**要加粗**的字')
    expect(out.text.slice(out.start, out.end)).toBe('要加粗')
  })

  it('无选区时插入占位符并选中它', () => {
    const out = applyMarkdownCommand('', 0, 0, 'code')
    expect(out.text).toBe('`代码`')
    expect(out.text.slice(out.start, out.end)).toBe('代码')
  })
})
