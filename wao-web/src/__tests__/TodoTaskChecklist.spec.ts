import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import TodoTaskChecklist from '@/components/todo/TodoTaskChecklist.vue'

describe('待办子事项阅读与操作边界', () => {
  it('保留个人顺序，历史合成键不会随展示顺序改变', async () => {
    const wrapper = mount(TodoTaskChecklist, { props: { taskId: 'task', order: ['task#1', 'task#0'], subs: [
      { text: '原来第一项', done: false }, { text: '个人排到最前的项', done: false },
    ] } })
    expect(wrapper.findAll('label span').map(item => item.text())).toEqual(['个人排到最前的项', '原来第一项'])
    await wrapper.findAll('input')[0]!.setValue(true)
    expect(wrapper.emitted('toggle')).toEqual([['task#1']])
    wrapper.unmount()
  })
  it('默认显示未完成和待验证事项，完成上级仍有未完成下级时保留层级', async () => {
    const wrapper = mount(TodoTaskChecklist, { props: { taskId: 'task', subs: [
      { id: 'parent', text: '已标完成的上级', done: true },
      { id: 'child', parentId: 'parent', text: '下级仍待验证', done: false, status: '待验证' },
      { id: 'done', text: '真正收尾的另一项', done: true },
    ] } })
    expect(wrapper.text()).toContain('已标完成的上级')
    expect(wrapper.text()).toContain('下级仍待验证')
    expect(wrapper.text()).not.toContain('真正收尾的另一项')
    expect(wrapper.findAll('input')).toHaveLength(2)
    await wrapper.get('button').trigger('click')
    expect(wrapper.findAll('input')).toHaveLength(3)
    await wrapper.get('button').trigger('click')
    expect(wrapper.findAll('input')).toHaveLength(2)
    wrapper.unmount()
  })
  it('兼容历史合成键；只读和保存中不会发出写入', async () => {
    const wrapper = mount(TodoTaskChecklist, { props: { taskId: 'task', subs: [{ text: '无稳定 ID 的旧事项', done: false }], readOnly: true } })
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
    await wrapper.get('input').setValue(true)
    expect(wrapper.emitted('toggle')).toBeUndefined()
    await wrapper.setProps({ readOnly: false, saving: true })
    expect(wrapper.get('input').attributes('disabled')).toBeDefined()
    await wrapper.setProps({ saving: false })
    await wrapper.get('input').setValue(false)
    await wrapper.get('input').setValue(true)
    const toggles = wrapper.emitted('toggle')
    expect(toggles?.[toggles.length - 1]).toEqual(['task#0'])
    wrapper.unmount()
  })
})
