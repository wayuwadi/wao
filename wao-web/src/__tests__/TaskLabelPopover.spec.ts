import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import TaskLabelEditor from '@/components/task/TaskLabelEditor.vue'

const fixture = vi.hoisted(() => ({ store: null as any }))
vi.mock('@/stores/taskLabels', () => ({ useTaskLabelsStore: () => fixture.store }))
vi.mock('@/composables/taskChangeFeedback', () => ({ labelFeedback: vi.fn() }))
enableAutoUnmount(afterEach)
const draftKey = 'wao-task-label-draft:account:member:t1:'
beforeEach(() => {
  localStorage.clear()
  fixture.store = reactive({
    identity: 'account:member', ready: true, loading: false, error: '', catalog: ['研发', '待确认'],
    get: () => ({ taskId: 't1', subId: '', revision: 4, tags: ['研发'] }),
    save: vi.fn(async (value: any) => ({ ...value, revision: 5 })), load: vi.fn(),
  })
})
afterEach(() => { document.body.innerHTML = '' })
function input() { return document.querySelector<HTMLInputElement>('[aria-label="任务标签，用逗号分隔"]')! }
async function open() {
  const wrapper = mount(TaskLabelEditor, { props: { taskId: 't1', inline: true }, attachTo: document.body })
  await wrapper.get('button[aria-haspopup="dialog"]').trigger('click')
  await flushPromises()
  return wrapper
}
async function enter(text: string) {
  input().value = text
  input().dispatchEvent(new Event('input', { bubbles: true }))
  await nextTick()
}

describe('任务标签紧凑浮层', () => {
  it('浮层挂到正文外，保持触发按钮并将焦点交给输入框', async () => {
    const wrapper = await open()
    expect(wrapper.find('form').exists()).toBe(false)
    expect(document.querySelector('.cell-menu form[role="dialog"]')).not.toBeNull()
    expect(wrapper.get('button[aria-haspopup="dialog"]').attributes('aria-expanded')).toBe('true')
    expect(document.activeElement).toBe(input())
  })
  it('Escape收起时保留草稿与原修订号，再打开继续编辑', async () => {
    const wrapper = await open()
    await enter('研发，尚未提交')
    input().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await nextTick()
    expect(document.querySelector('.cell-menu')).toBeNull()
    expect(document.activeElement).toBe(wrapper.get('button[aria-haspopup="dialog"]').element)
    expect(JSON.parse(localStorage.getItem(draftKey)!)).toEqual({ text: '研发，尚未提交', revision: 4 })
    await wrapper.get('button[aria-haspopup="dialog"]').trigger('click')
    await flushPromises()
    expect(input().value).toBe('研发，尚未提交')
    expect(fixture.store.save).not.toHaveBeenCalled()
  })
  it('点外部只关闭浮层，明确取消才清除草稿', async () => {
    const wrapper = await open()
    await enter('草稿')
    document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await nextTick()
    expect(localStorage.getItem(draftKey)).not.toBeNull()
    await wrapper.get('button[aria-haspopup="dialog"]').trigger('click')
    await flushPromises()
    const cancel = [...document.querySelectorAll<HTMLButtonElement>('.cell-menu button')].find(b => b.textContent === '取消')!
    cancel.click()
    await nextTick()
    expect(localStorage.getItem(draftKey)).toBeNull()
    expect(wrapper.emitted('done')).toHaveLength(1)
  })
  it('保存失败保留输入和错误，重试使用原任务与修订号', async () => {
    fixture.store.save.mockRejectedValueOnce(new Error('offline'))
    const wrapper = await open()
    await enter('研发，研发，新标签')
    document.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()
    expect(input().value).toBe('研发，研发，新标签')
    expect(document.querySelector('.cell-menu [role="alert"]')?.textContent).toContain('草稿已保留')
    expect(localStorage.getItem(draftKey)).not.toBeNull()
    document.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await flushPromises()
    expect(fixture.store.save).toHaveBeenLastCalledWith({ taskId: 't1', subId: '', revision: 4, tags: ['研发', '新标签'] })
    expect(localStorage.getItem(draftKey)).toBeNull()
    expect(wrapper.emitted('done')).toHaveLength(1)
  })
  it('保存期间不能被Escape或外部点击打断', async () => {
    let resolve!: (value: unknown) => void
    fixture.store.save.mockReturnValueOnce(new Promise(r => { resolve = r }))
    await open()
    document.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await nextTick()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
    await nextTick()
    expect(document.querySelector('.cell-menu')).not.toBeNull()
    expect(input().disabled).toBe(true)
    resolve({ taskId: 't1', subId: '', revision: 5, tags: ['研发'] })
    await flushPromises()
    expect(document.querySelector('.cell-menu')).toBeNull()
  })
  it('独立快捷窗口自动打开时沿用内嵌表单，不再套一层浮层', async () => {
    const wrapper = mount(TaskLabelEditor, { props: { taskId: 't1', autoOpen: true }, attachTo: document.body })
    await flushPromises()
    expect(wrapper.find('form').exists()).toBe(true)
    expect(document.querySelector('.cell-menu')).toBeNull()
    expect(document.activeElement).toBe(input())
  })
})
