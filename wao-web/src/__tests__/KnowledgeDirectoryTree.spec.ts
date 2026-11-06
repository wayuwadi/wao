import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, mount } from '@vue/test-utils'
import { h } from 'vue'
import Tree from '@/components/knowledge/KnowledgeDirectoryTree.vue'
import type { DirectoryNode } from '@/utils/knowledgeDirectory'

enableAutoUnmount(afterEach)
const nodes: DirectoryNode[] = [
  { key: 'f:one', id: 'one', kind: 'folder', title: '工作', depth: 0, parent: '', hasChildren: true },
  { key: 'd:a', id: 'a', kind: 'doc', title: 'Alpha', depth: 1, parent: 'f:one', hasChildren: false },
  { key: 'd:b', id: 'b', kind: 'doc', title: 'Beta', depth: 1, parent: 'f:one', hasChildren: true },
  { key: 'd:c', id: 'c', kind: 'doc', title: 'Child', depth: 2, parent: 'd:b', hasChildren: false },
]
const render = (expanded: Record<string, boolean> = {}) => mount(Tree, {
  attachTo: document.body,
  props: { nodes, expanded, label: '工作目录' },
})
describe('可访问知识库目录', () => {
  it('单一Tab入口、方向键和Home/End浏览可见节点', async () => {
    const wrapper = render()
    expect(wrapper.findAll('[tabindex="0"]')).toHaveLength(1)
    expect(wrapper.get('[role="tree"]').attributes('aria-label')).toBe('工作目录')
    expect(wrapper.get('[data-tree-key="d:c"]').attributes('aria-level')).toBe('3')
    await wrapper.get('[data-tree-key="f:one"]').trigger('keydown', { key: 'ArrowDown' })
    expect(document.activeElement).toBe(wrapper.get('[data-tree-key="d:a"]').element)
    await wrapper.get('[data-tree-key="d:a"]').trigger('keydown', { key: 'End' })
    expect(document.activeElement).toBe(wrapper.get('[data-tree-key="d:c"]').element)
    await wrapper.get('[data-tree-key="d:c"]').trigger('keydown', { key: 'ArrowLeft' })
    expect(document.activeElement).toBe(wrapper.get('[data-tree-key="d:b"]').element)
    await wrapper.get('[data-tree-key="d:b"]').trigger('keydown', { key: 'Home' })
    expect(document.activeElement).toBe(wrapper.get('[data-tree-key="f:one"]').element)
  })
  it('左右键展开折叠，文档点击打开而箭头只折叠子目录', async () => {
    const wrapper = render({ 'f:one': false })
    await wrapper.get('[data-tree-key="f:one"]').trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.emitted('toggle')?.[0]).toEqual(['f:one', true])
    await wrapper.setProps({ expanded: { 'f:one': true } })
    await wrapper.get('[data-tree-key="f:one"]').trigger('keydown', { key: 'ArrowRight' })
    expect(document.activeElement).toBe(wrapper.get('[data-tree-key="d:a"]').element)
    await wrapper.get('[data-tree-key="d:b"]').trigger('click')
    expect(wrapper.emitted('open')).toEqual([['b']])
    await wrapper.get('[data-tree-key="d:b"] .tree-caret').trigger('click')
    const toggles = wrapper.emitted('toggle')!
    expect(toggles[toggles.length - 1]).toEqual(['d:b', false])
    expect(wrapper.emitted('open')).toHaveLength(1)
  })
  it('搜索自动展开且不覆盖用户折叠选择，字母键可跳转', async () => {
    const wrapper = render({ 'f:one': false, 'd:b': false })
    await wrapper.setProps({ searching: true })
    expect(wrapper.get('[data-tree-key="f:one"]').attributes('aria-expanded')).toBe('true')
    await wrapper.get('[data-tree-key="f:one"]').trigger('click')
    expect(wrapper.emitted('toggle')).toBeUndefined()
    await wrapper.get('[data-tree-key="f:one"]').trigger('keydown', { key: 'b' })
    expect(document.activeElement).toBe(wrapper.get('[data-tree-key="d:b"]').element)
    await wrapper.setProps({ searching: false })
    expect(wrapper.get('[data-tree-key="f:one"]').attributes('aria-expanded')).toBe('false')
  })
  it('IME输入和修饰快捷键不触发目录导航，节点移除后仍保留Tab入口', async () => {
    const wrapper = render()
    const focus = vi.spyOn(wrapper.get('[data-tree-key="d:a"]').element as HTMLElement, 'focus')
    await wrapper.get('[data-tree-key="f:one"]').trigger('keydown', { key: 'ArrowDown', isComposing: true })
    await wrapper.get('[data-tree-key="f:one"]').trigger('keydown', { key: 'ArrowDown', ctrlKey: true })
    expect(focus).not.toHaveBeenCalled()
    await wrapper.get('[data-tree-key="d:c"]').trigger('focus')
    await wrapper.setProps({ nodes: nodes.slice(0, 2) })
    expect(wrapper.findAll('[tabindex="0"]')).toHaveLength(1)
  })
  it('首页可复用标题与时间插槽，预加载只来自文档悬停或聚焦', async () => {
    const wrapper = mount(Tree, {
      props: { nodes, expanded: {}, label: '首页目录', density: 'comfortable' },
      slots: {
        title: ({ node }: { node: DirectoryNode }) => h('span', { class: 'home-document-name' }, node.title),
        suffix: ({ node }: { node: DirectoryNode }) => node.kind === 'doc' ? h('time', '今天') : null,
      },
    })
    expect(wrapper.findAll('.home-document-name')).toHaveLength(4)
    expect(wrapper.findAll('time')).toHaveLength(3)
    expect(wrapper.get('.tree-node').classes()).toContain('tree-node--comfortable')
    await wrapper.get('[data-tree-key="f:one"]').trigger('pointerenter')
    expect(wrapper.emitted('preload')).toBeUndefined()
    await wrapper.get('[data-tree-key="d:a"]').trigger('pointerenter')
    await wrapper.get('[data-tree-key="d:b"]').trigger('focus')
    expect(wrapper.emitted('preload')).toEqual([['a'], ['b']])
  })
  it('一万条目录分批渲染，End到已显示末项，更多后聚焦新增首项', async () => {
    const large: DirectoryNode[] = Array.from({ length: 10000 }, (_, index) => ({
      key: `d:${index}`, id: `${index}`, parent: '', title: `文档 ${index}`, kind: 'doc', depth: 0, hasChildren: false,
    }))
    const wrapper = mount(Tree, { attachTo: document.body, props: { nodes: large, expanded: {}, label: '大目录' } })
    expect(wrapper.findAll('[role="treeitem"]')).toHaveLength(500)
    expect(wrapper.get('[data-tree-key="d:0"]').attributes('aria-setsize')).toBe('10000')
    expect(wrapper.get('.directory-more').text()).toContain('剩余 9500 项')
    await wrapper.get('[data-tree-key="d:0"]').trigger('keydown', { key: 'End' })
    expect(document.activeElement).toBe(wrapper.get('[data-tree-key="d:499"]').element)
    await wrapper.get('.directory-more').trigger('click')
    expect(wrapper.findAll('[role="treeitem"]')).toHaveLength(1000)
    expect(document.activeElement).toBe(wrapper.get('[data-tree-key="d:500"]').element)
    await wrapper.setProps({ nodes: [large[9999]!], searching: true })
    expect(wrapper.findAll('[role="treeitem"]')).toHaveLength(1)
    expect(wrapper.get('[role="treeitem"]').text()).toContain('文档 9999')
    expect(wrapper.find('.directory-more').exists()).toBe(false)
  })
})
