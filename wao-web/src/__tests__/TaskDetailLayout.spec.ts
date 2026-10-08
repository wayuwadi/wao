import { afterEach, describe, expect, it } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import TaskDetailLayout from '@/components/task/TaskDetailLayout.vue'

enableAutoUnmount(afterEach)
function layout() {
  return mount(TaskDetailLayout, {
    attachTo: document.body,
    props: {
      contextKey: 'parent',
      description: '# 背景\n\n长描述'.repeat(200) + '\n![长图](https://example.invalid/image.png)',
      subtaskCount: 4,
    },
    slots: {
      heading: '<h2>任务标题</h2>',
      description: '<textarea aria-label="描述草稿"></textarea>',
      subtasks: '<input aria-label="新增子任务草稿" />',
      properties: '<textarea aria-label="评论草稿"></textarea>',
    },
  })
}

describe('任务详情分区与浏览状态', () => {
  it('长描述和图片仅预览原文，首屏子任务可见且全文仍可访问', async () => {
    const wrapper = layout()
    expect(wrapper.get('[data-section="overview"]').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('input').isVisible()).toBe(true)
    expect(wrapper.get('textarea[aria-label="描述草稿"]').isVisible()).toBe(false)
    expect(wrapper.find('.description-preview img').exists()).toBe(false)
    expect(wrapper.get('.description-preview p').text().length).toBeLessThanOrEqual(280)
    expect(wrapper.get('.preview-image-hint').text()).toContain('包含图片')
    await wrapper.get('.preview-heading button').trigger('click')
    expect(wrapper.get('textarea[aria-label="描述草稿"]').isVisible()).toBe(true)
    expect(wrapper.get('input').isVisible()).toBe(false)
  })

  it('页签切换不重建编辑器或列表，描述和新增子任务草稿均保留', async () => {
    const wrapper = layout()
    const editor = wrapper.get('textarea[aria-label="描述草稿"]')
    const input = wrapper.get('input')
    await input.setValue('尚未回车的子任务')
    await wrapper.get('[data-section="description"]').trigger('click')
    await editor.setValue('正在输入的描述')
    await wrapper.get('[data-section="subtasks"]').trigger('click')
    expect(wrapper.get('input').element).toBe(input.element)
    expect((input.element as HTMLInputElement).value).toBe('尚未回车的子任务')
    await wrapper.get('[data-section="description"]').trigger('click')
    expect(wrapper.get('textarea[aria-label="描述草稿"]').element).toBe(editor.element)
    expect((editor.element as HTMLTextAreaElement).value).toBe('正在输入的描述')
  })

  it('打开下级再返回恢复原分区和滚动位置，不继承下级的位置', async () => {
    const wrapper = layout()
    await wrapper.get('[data-section="subtasks"]').trigger('click')
    const scroll = wrapper.get('.detail-scroll').element
    scroll.scrollTop = 260
    await wrapper.get('.detail-scroll').trigger('scroll')
    await wrapper.setProps({ contextKey: 'child', subtaskCount: 1 })
    await nextTick()
    expect(wrapper.get('[data-section="overview"]').attributes('aria-selected')).toBe('true')
    expect(scroll.scrollTop).toBe(0)
    await wrapper.setProps({ contextKey: 'parent', subtaskCount: 4 })
    await nextTick()
    expect(wrapper.get('[data-section="subtasks"]').attributes('aria-selected')).toBe('true')
    expect(scroll.scrollTop).toBe(260)
  })

  it('属性与评论收起不丢输入，Esc 收起并将焦点返回属性入口', async () => {
    const wrapper = layout()
    const toggle = wrapper.get('.properties-toggle')
    expect(wrapper.get('.detail-properties').isVisible()).toBe(false)
    await toggle.trigger('click')
    const comment = wrapper.get('textarea[aria-label="评论草稿"]')
    await comment.setValue('未发送的评论')
    await comment.trigger('keydown', { key: 'Escape' })
    await nextTick()
    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(toggle.element)
    await toggle.trigger('click')
    expect((comment.element as HTMLTextAreaElement).value).toBe('未发送的评论')
  })

  it('支持方向键与首尾导航，空描述给出真实空态且数量随数据刷新', async () => {
    const wrapper = layout()
    await wrapper.setProps({ description: '', subtaskCount: 0 })
    expect(wrapper.get('.description-preview').text()).toContain('暂无描述')
    expect(wrapper.get('.preview-heading button').text()).toContain('添加描述')
    await wrapper.get('[data-section="overview"]').trigger('keydown', { key: 'End' })
    expect(wrapper.get('[data-section="subtasks"]').attributes('aria-selected')).toBe('true')
    expect(document.activeElement).toBe(wrapper.get('[data-section="subtasks"]').element)
    await wrapper.get('[data-section="subtasks"]').trigger('keydown', { key: 'ArrowLeft' })
    expect(wrapper.get('[data-section="description"]').attributes('aria-selected')).toBe('true')
    await wrapper.get('[data-section="description"]').trigger('keydown', { key: 'Home' })
    expect(wrapper.get('[data-section="overview"]').attributes('aria-selected')).toBe('true')
    await wrapper.setProps({ subtaskCount: 12 })
    expect(wrapper.get('[data-section="subtasks"]').text()).toBe('子任务 12')
  })
})
