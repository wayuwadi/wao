import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ArticleBodyEditor from './ArticleBodyEditor.vue'

describe('文章正文嵌入编辑器', () => {
  it('选择语言后在光标处插入可持久化的代码块', async () => {
    const wrapper = mount(ArticleBodyEditor, { props: { modelValue: '前文\n\n后文' } })
    const body = wrapper.get<HTMLTextAreaElement>('textarea[aria-label="文章正文"]')
    body.element.setSelectionRange(4, 4)
    await wrapper.findAll('.embed-toolbar button')[1]!.trigger('click')
    await wrapper.get('select').setValue('python')
    await wrapper.get('.embed-dialog textarea').setValue('print("WAO")')
    await wrapper.get('form').trigger('submit')

    const updates = wrapper.emitted('update:modelValue') as [string][]
    expect(updates.at(-1)?.[0]).toContain('```python\nprint("WAO")\n```')
    expect(wrapper.emitted('change')).toHaveLength(1)
  })

  it('可选择三类画图模板并以 Mermaid 块写入正文', async () => {
    const wrapper = mount(ArticleBodyEditor, { props: { modelValue: '正文' } })
    await wrapper.findAll('.embed-toolbar button')[0]!.trigger('click')
    await wrapper.get('select').setValue('sequenceDiagram')
    await wrapper.get('form').trigger('submit')

    const updates = wrapper.emitted('update:modelValue') as [string][]
    expect(updates.at(-1)?.[0]).toContain('```mermaid\nsequenceDiagram')
    expect(updates.at(-1)?.[0]).toContain('用户->>WAO: 发起请求')
  })

  it('只读文章禁用嵌入入口且不会修改正文', async () => {
    const wrapper = mount(ArticleBodyEditor, { props: { modelValue: '已发布正文', disabled: true } })
    expect(wrapper.get('textarea[aria-label="文章正文"]').attributes('disabled')).toBeDefined()
    expect(wrapper.findAll('.embed-toolbar button').every((button) => button.attributes('disabled') !== undefined)).toBe(true)
    await wrapper.findAll('.embed-toolbar button')[0]!.trigger('click')
    expect(wrapper.find('.embed-dialog').exists()).toBe(false)
  })
})
