import { describe, expect, it } from 'vitest'
import { imageDisplayName } from '@/utils/imageDisplayName'
import { mount } from '@vue/test-utils'
import NoteReadingImages from '@/components/notes/NoteReadingImages.vue'

describe('小记图片名称', () => {
  it.each(['img_v3_0216b_744a2bc5-9996-4179-9d32-331d5200487g.jpg', 'codex-clipboard-a12b.png', '7baa05b08e644efa99babd1f413d1a38.jpg', 'image.png', '截图20261010.png', '', undefined])('机器文件名 %s 仅在显示时使用序号', name => {
    expect(imageDisplayName(name, 2)).toBe('图片 2')
  })
  it.each(['文章总览图', '任务进展 [第 1 周]', '公园散步.jpg', '图片排版说明'])('保留有意义的名称 %s', name => {
    expect(imageDisplayName(name, 1)).toBe(name)
  })
  it('图片清单改名入口指向当前序号，阅读模式保持只读且不改原数据', async () => {
    const images = [{ src: '/api/images/one/raw', alt: 'image.png' }, { src: '/api/images/one/raw', alt: '长名称：任务进展' }]
    const w = mount(NoteReadingImages, { props: { images, active: null, editable: true } })
    expect(w.text()).toContain('图片 1')
    await w.get('[aria-label="修改图 2 的名称"]').trigger('click')
    expect(w.emitted('rename')?.[0]?.[0]).toBe(2)
    expect(images[0]?.alt).toBe('image.png')
    await w.setProps({ editable: false })
    expect(w.find('.image-name-button').exists()).toBe(false)
    expect(w.get('img').attributes('alt')).toBe('图片 1')
    w.unmount()
  })
})
