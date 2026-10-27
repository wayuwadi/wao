import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { projectWorkItems } from '@/utils/projectWorkItems'
import ProjectChecklistView from '@/components/task/ProjectChecklistView.vue'
import { seedDataStore } from './helpers/seedDataStore'

enableAutoUnmount(afterEach)
beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); seedDataStore() })
function setup(error = false) {
  const data = useDataStore()
  data.tasks = [{ ...data.tasks[0]!, id: 'parent', title: '父任务', desc: '父正文', status: '待办', subs: [
    { id: 'child', text: '子任务', desc: '子正文', done: false },
  ] }]
  if (error) {
    vi.spyOn(data, 'taskSaveState').mockReturnValue('error')
    vi.spyOn(data, 'taskSaveError').mockReturnValue('服务器已有新版本')
  }
  const projection = projectWorkItems(data.tasks)
  const wrapper = mount(ProjectChecklistView, {
    props: { items: projection.items, contextItems: projection.items, itemRefs: projection.refs, showDone: false, collapsed: [], query: '' },
    global: { stubs: { MarkdownEditor: { name: 'MarkdownEditor', props: ['modelValue', 'bare'], template: '<div>{{ modelValue }}</div>' } } },
  })
  return { data, wrapper }
}
describe('项目清单详情', () => {
  it('点击子任务只切换右侧卡片，Markdown 写入真实子事项而非父正文', async () => {
    const { data, wrapper } = setup()
    const update = vi.spyOn(data, 'updateSub').mockImplementation(() => undefined)
    const patch = vi.spyOn(data, 'patchTask').mockImplementation(() => undefined)
    await wrapper.findAll('.task-select')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.get('h2').text()).toBe('子任务')
    expect(useUiStore().openTaskId).toBeFalsy()
    wrapper.findComponent({ name: 'MarkdownEditor' }).vm.$emit('update:modelValue', '## 子任务记录')
    await flushPromises()
    expect(update).toHaveBeenCalledWith('parent', 'child', { desc: '## 子任务记录' })
    expect(patch).not.toHaveBeenCalled()
    await wrapper.findAll('.task-select')[0]!.trigger('click')
    expect(wrapper.find('textarea').exists()).toBe(false)
  })
  it('查找子任务保留父级，折叠和筛选移除当前选择后不会展示旧详情', async () => {
    const { wrapper } = setup()
    await wrapper.setProps({ query: '子正文', collapsed: ['parent'] })
    expect(wrapper.findAll('.task-select')).toHaveLength(2)
    await wrapper.findAll('.task-select')[1]!.trigger('click')
    await wrapper.setProps({ query: '', collapsed: ['parent'] })
    expect(wrapper.findAll('.task-select')).toHaveLength(1)
    expect(wrapper.get('h2').text()).toBe('父任务')
    await wrapper.setProps({ query: '不存在' })
    expect(wrapper.find('h2').exists()).toBe(false)
    expect(wrapper.text()).toContain('没有匹配的任务')
  })
  it('保存错误保留内容并提供原任务冲突处理入口', async () => {
    const { wrapper } = setup(true)
    await wrapper.setProps({ query: '父' })
    await flushPromises()
    expect(wrapper.text()).toContain('服务器已有新版本')
    await wrapper.findAll('button').find(b => b.text() === '查看保存问题')!.trigger('click')
    expect(useUiStore().openTaskId).toBe('parent')
  })
})
