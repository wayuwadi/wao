import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TodoDayLayout from '@/components/todo/TodoDayLayout.vue'

describe('按天辅助侧边栏', () => {
  it('默认折叠，打开一个面板时任务仍在，切换与收起保留输入节点和草稿', async () => {
    const wrapper = mount(TodoDayLayout, { attachTo: document.body, slots: {
      default: '<p>今天要做的事</p>', routine: '<p>早晚固定清单</p>', notes: '<textarea aria-label="随手记草稿"></textarea>',
    } })
    const buttons = wrapper.findAll('.day-side-rail button'), panels = wrapper.findAll('.day-side-panels > section')
    expect(buttons.every(button => button.attributes('aria-expanded') === 'false')).toBe(true)
    expect(wrapper.get('.day-side-panels').isVisible()).toBe(false)
    await buttons[1]!.trigger('click')
    const editor = wrapper.get<HTMLTextAreaElement>('textarea')
    await editor.setValue('尚未保存的小记')
    const node = editor.element
    expect(panels[1]!.isVisible()).toBe(true)
    expect(wrapper.get('.day-primary').text()).toBe('今天要做的事')
    await buttons[0]!.trigger('click')
    expect(panels[0]!.isVisible()).toBe(true)
    expect(panels[1]!.isVisible()).toBe(false)
    await buttons[1]!.trigger('click')
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element).toBe(node)
    expect(editor.element.value).toBe('尚未保存的小记')
    await wrapper.get('[aria-label="收起当天辅助面板"]').trigger('click')
    expect(wrapper.get('.day-side-panels').isVisible()).toBe(false)
    expect(document.activeElement).toBe(buttons[1]!.element)
    await buttons[1]!.trigger('click')
    expect(editor.element.value).toBe('尚未保存的小记')
    await editor.trigger('keydown', { key: 'Escape' })
    expect(wrapper.get('.day-side-panels').isVisible()).toBe(false)
    expect(document.activeElement).toBe(buttons[1]!.element)
    wrapper.unmount()
  })
  it('日期或成员改变时重置为折叠，所有入口引用各自唯一面板', async () => {
    const wrapper = mount(TodoDayLayout, { props: { resetKey: 'account:member:2026-10-14' }, slots: { routine: '<p>固定</p>', notes: '<p>记录</p>' } })
    const buttons = wrapper.findAll('.day-side-rail button')
    for (const button of buttons) expect(wrapper.get(`#${button.attributes('aria-controls')}`).attributes('aria-labelledby')).toBe(button.attributes('id'))
    await buttons[0]!.trigger('click')
    await wrapper.setProps({ resetKey: 'account:member:2026-10-15' })
    expect(buttons[0]!.attributes('aria-expanded')).toBe('false')
    await buttons[1]!.trigger('click')
    await wrapper.setProps({ resetKey: 'another:member:2026-10-15' })
    expect(buttons[1]!.attributes('aria-expanded')).toBe('false')
    expect(wrapper.get('.day-side-panels').isVisible()).toBe(false)
    wrapper.unmount()
  })
})
