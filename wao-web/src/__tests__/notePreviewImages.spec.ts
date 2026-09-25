import { describe, expect, it } from 'vitest'
import { notePreviewImages, notePreviewSource } from '@/utils/notePreviewImages'

describe('小记图片预览提取', () => {
  it('图床小图使用预览地址，放大仍保留原图地址', () => {
    expect(notePreviewSource('/api/images/img123/raw')).toBe('/api/images/img123/preview')
    expect(notePreviewSource('https://wao.wayuwa.cn/api/images/img123/raw')).toBe('https://wao.wayuwa.cn/api/images/img123/preview')
    expect(notePreviewSource('https://example.com/other.png')).toBe('https://example.com/other.png')
  })
  it('优先读取富文本结构，保留嵌套和重复图片的顺序', () => {
    expect(notePreviewImages({ version: 1, markdown: '![旧图](/api/images/old/raw)', document: {
      type: 'doc', content: [
        { type: 'blockquote', content: [{ type: 'image', attrs: { src: '/api/images/one/raw', alt: '第一张' } }] },
        { type: 'image', attrs: { src: '/api/images/one/raw', alt: '第二张' } },
        { type: 'image', attrs: { src: 'javascript:alert(1)', alt: '无效' } },
      ],
    } })).toEqual([
      { src: '/api/images/one/raw', alt: '第一张' },
      { src: '/api/images/one/raw', alt: '第二张' },
    ])
  })

  it('旧 Markdown 图片在列表与正文里也显示缩略图', () => {
    expect(notePreviewImages({ version: 1, document: null,
      markdown: '一段话\n\n- ![列表图](/api/images/one/raw "wao-image-size:50%xauto")\n\n![正文图](https://example.com/two.png)',
    })).toEqual([
      { src: '/api/images/one/raw', alt: '列表图' },
      { src: 'https://example.com/two.png', alt: '正文图' },
    ])
  })
})
