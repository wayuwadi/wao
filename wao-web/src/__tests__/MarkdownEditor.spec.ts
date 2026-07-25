import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import MarkdownEditor from '@/components/ui/MarkdownEditor.vue'

describe('MarkdownEditor', () => {
  it('默认是预览态，展示渲染结果而不是源码', () => {
    const w = mount(MarkdownEditor, { props: { modelValue: '## 目标\n\n**重点**' } })

    expect(w.find('textarea').exists()).toBe(false)
    expect(w.find('.md-view').html()).toContain('<h2>目标</h2>')
    expect(w.text()).not.toContain('##')
  })

  it('点击预览进入编辑态，看到的是源码', async () => {
    const w = mount(MarkdownEditor, { props: { modelValue: '## 目标' } })
    await w.find('.md-view').trigger('click')

    expect(w.find('textarea').element.value).toBe('## 目标')
  })

  it('内容为空时展示 placeholder，点击也能进入编辑', async () => {
    const w = mount(MarkdownEditor, { props: { modelValue: '', placeholder: '写点什么' } })
    expect(w.find('.md-empty').text()).toBe('写点什么')

    await w.find('.md-empty').trigger('click')
    expect(w.find('textarea').exists()).toBe(true)
  })

  it('输入时向上抛 update:modelValue', async () => {
    const w = mount(MarkdownEditor, { props: { modelValue: '', alwaysEdit: true } })
    const ta = w.find('textarea')
    ta.element.value = '新内容'
    await ta.trigger('input')

    expect(w.emitted('update:modelValue')?.[0]).toEqual(['新内容'])
  })

  it('工具条按钮套用 Markdown 快捷命令', async () => {
    const w = mount(MarkdownEditor, { props: { modelValue: '一条待办', alwaysEdit: true } })
    await w.find('.md-tool[title="无序列表"]').trigger('mousedown')

    expect(w.emitted('update:modelValue')?.[0]).toEqual(['- 一条待办'])
  })

  it('只读模式不进入编辑态', async () => {
    const w = mount(MarkdownEditor, { props: { modelValue: '内容', readonly: true } })
    await w.find('.md-view').trigger('click')

    expect(w.find('textarea').exists()).toBe(false)
  })

  it('alwaysEdit 时常驻编辑态，失焦也不收起', async () => {
    const w = mount(MarkdownEditor, { props: { modelValue: '', alwaysEdit: true } })
    expect(w.find('textarea').exists()).toBe(true)

    await w.find('textarea').trigger('blur')
    expect(w.find('textarea').exists()).toBe(true)
  })
})
