import { mount } from '@vue/test-utils'
import { describe, it, expect, vi } from 'vitest'
import NoteReadingImages from '@/components/notes/NoteReadingImages.vue'
import { copyText } from '@/utils/clipboard'
vi.mock('@/utils/clipboard', () => ({ copyText: vi.fn() }))

describe('独立思考图', () => {
  it('保留原图，衍生图显示来源和旧版本提示，不伪造正文位置', async () => {
    const w = mount(NoteReadingImages, { props: { active: null, images: [
      { src: '/api/images/cover/raw', alt: '文章总览图' },
      { src: '/api/images/thought/raw', alt: '思考图 · 关键关系', derived: true, sourceRev: 3, current: false, outline: '原文观察\nAI推测\n待验证问题', questions: ['哪一个条件值得验证？'] },
    ] } })
    expect(w.findAll('figure')).toHaveLength(2)
    expect(w.findAll('img').map(img => img.attributes('src'))).toEqual(['/api/images/cover/preview', '/api/images/thought/preview'])
    expect(w.text()).toContain('来源 v3'); expect(w.text()).toContain('原文已更新，仅供对照')
    expect(w.findAll('button').filter(button => button.text().includes('返回正文'))).toHaveLength(1)
    await w.get('.thinking-question').trigger('click')
    expect(w.emitted('question')).toEqual([['哪一个条件值得验证？']])
    await w.findAll('button').find(button => button.text() === '复制提纲')!.trigger('click')
    expect(copyText).toHaveBeenCalledWith('原文观察\nAI推测\n待验证问题', '思考提纲已复制')
    w.unmount()
  })
})
