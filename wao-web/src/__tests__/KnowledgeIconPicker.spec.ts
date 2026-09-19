import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import KnowledgeIconPicker from '@/components/knowledge/KnowledgeIconPicker.vue'

describe('知识库图标选择', () => {
  it('兼容旧图标，选中彩色图标后关闭并向设置传递持久化值', async () => {
    const wrapper = mount(KnowledgeIconPicker, { props: { modelValue: 'ri-book-2-line' } })
    expect(wrapper.text()).toContain('原有图标')
    await wrapper.get('[aria-label="选择知识库图标"]').trigger('click')
    expect(wrapper.findAll('.icon-options button')).toHaveLength(18)
    await wrapper.get('[aria-label="日历"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')?.[0]).toEqual(['wao-kb-icon-calendar'])
    expect(wrapper.find('.icon-options').exists()).toBe(false)
  })
  it('保存期间禁用，Esc 可以关闭选择区', async () => {
    const wrapper = mount(KnowledgeIconPicker, { props: { modelValue: 'wao-kb-icon-code' } })
    await wrapper.get('.icon-choice').trigger('click')
    expect(wrapper.get('[aria-label="代码"]').attributes('aria-pressed')).toBe('true')
    await wrapper.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.icon-options').exists()).toBe(false)
    await wrapper.setProps({ disabled: true })
    expect(wrapper.get('.icon-choice').attributes('disabled')).toBeDefined()
  })
})
