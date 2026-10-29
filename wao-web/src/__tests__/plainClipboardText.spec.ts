import { describe, expect, it } from 'vitest'
import { plainClipboardText } from '@/utils/richClipboard'

describe('普通复制的可读原文', () => {
  it('保留下划线和反斜杠路径，不插入转义和链接语法', () => {
    expect(plainClipboardText('<ul><li><p>ads.fact_demo_sellin 新增goods_type</p></li></ul>'))
      .toBe('- ads.fact_demo_sellin 新增goods_type')
    expect(plainClipboardText(String.raw`<ol><li><p>dws\demo\fact_demo_<a href="http://sellin.py">sellin.py</a></p></li></ol>`))
      .toBe(String.raw`1. dws\demo\fact_demo_sellin.py`)
  })
  it('保留嵌套编号、代码空白、换行和表格单元格', () => {
    expect(plainClipboardText('<ol start="3"><li><p>父</p><ul><li><p>子</p></li></ul></li><li>下项</li></ol>'))
      .toBe('3. 父\n\n  - 子\n4. 下项')
    expect(plainClipboardText('<pre><code>  a_b\n    c\\d</code></pre>')).toBe('  a_b\n    c\\d')
    expect(plainClipboardText('<table><tr><td>a_b</td><td>c</td></tr></table>')).toBe('a_b\tc')
  })
})
