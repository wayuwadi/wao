import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ConfirmActionDialog from '@/components/ui/ConfirmActionDialog.vue'
import DataLoadStatus from '@/components/ui/DataLoadStatus.vue'
import SaveHint from '@/components/ui/SaveHint.vue'
import SettingsSearch from '@/components/settings/SettingsSearch.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import type { DataDomain, DataLoadState } from '@/stores/data'
import { DATA_DOMAIN_LABELS } from '@/utils/pageDataDomains'

enableAutoUnmount(afterEach)
afterEach(() => { document.body.innerHTML = '' })

describe('确认操作的可恢复反馈', () => {
  it('初始焦点反向 Tab 留在弹窗内，跳过隐藏控件并包含链接', async () => {
    mount(SettingsDialogShell, { props: { title: '键盘演示' }, slots: { default: '<div style="display:none"><button>隐藏按钮</button></div><a href="#help">帮助</a>', footer: '<button>取消</button>' }, attachTo: document.body })
    await flushPromises()
    const dialog = document.querySelector<HTMLElement>('[role="dialog"]')!
    expect(document.activeElement).toBe(dialog)
    dialog.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }))
    expect(document.activeElement?.textContent).toBe('取消')
    // jsdom 不执行默认 Tab 行为，手动聚焦首项再验证边界循环。
    const close = document.querySelector<HTMLElement>('.dialog-close')!
    close.focus()
    close.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }))
    expect(document.activeElement?.textContent).toBe('取消')
  })
  it('确认前不执行；失败保留原因；重试期间禁止重复提交和关闭', async () => {
    let finish!: () => void
    const run = vi.fn<() => Promise<void>>().mockRejectedValueOnce(new Error('删除失败，文件仍保留')).mockImplementationOnce(() => new Promise<void>(resolve => { finish = resolve }))
    const wrapper = mount(ConfirmActionDialog, { props: { action: { title: '删除文件', description: '删除示例.txt，不可恢复。', run } }, attachTo: document.body })
    const button = () => document.querySelector<HTMLButtonElement>('.confirmation-actions .wao-button--danger')!
    expect(run).not.toHaveBeenCalled()
    button().click()
    await flushPromises()
    expect(document.querySelector('[role="alert"]')?.textContent).toContain('文件仍保留')
    expect(wrapper.emitted('close')).toBeUndefined()
    button().click()
    await flushPromises()
    expect(button().disabled).toBe(true)
    expect(document.querySelector<HTMLButtonElement>('.dialog-close')?.disabled).toBe(true)
    button().click()
    document.querySelector('.settings-dialog-mask')!.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await flushPromises()
    expect(run).toHaveBeenCalledTimes(2)
    expect(wrapper.emitted('close')).toBeUndefined()
    finish()
    await flushPromises()
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('取消不执行删除', async () => {
    const run = vi.fn<() => void>()
    const wrapper = mount(ConfirmActionDialog, { props: { action: { title: '删除文件', description: '示例', run } }, attachTo: document.body })
    document.querySelector<HTMLButtonElement>('.confirmation-actions .wao-button--secondary')!.click()
    await flushPromises()
    expect(run).not.toHaveBeenCalled()
    expect(wrapper.emitted('close')).toHaveLength(1)
  })
})

it('其他模块仍加载时可重试当前失败模块，次要错误默认折叠', async () => {
  const states = Object.fromEntries(Object.keys(DATA_DOMAIN_LABELS).map(domain => [domain, 'loaded'])) as Record<DataDomain, DataLoadState>
  states.docs = 'error'; states.files = 'error'; states.tasks = 'loading'
  const wrapper = mount(DataLoadStatus, { props: { states, errors: { docs: '文档超时', files: '文件超时' }, activeDomains: ['docs'] } })
  expect(wrapper.get('[role="alert"]').text()).toContain('文档超时')
  expect(wrapper.get('[role="alert"]').text()).not.toContain('文件超时')
  expect(wrapper.get('details').attributes('open')).toBeUndefined()
  expect(wrapper.find('[role="status"]').exists()).toBe(false)
  await wrapper.get('[aria-label="重新读取文档"]').trigger('click')
  expect(wrapper.emitted('retry')).toEqual([['docs']])
})

it('保存重试覆盖旧错误，草稿不误报为已同步', async () => {
  const wrapper = mount(SaveHint, { props: { failed: true, errorMessage: '网络中断', savedAt: '2026-10-29T09:00:00Z' } })
  expect(wrapper.get('[role="status"]').text()).toBe('网络中断')
  await wrapper.setProps({ saving: true })
  expect(wrapper.text()).toBe('保存中…')
  await wrapper.setProps({ saving: false, failed: false, draft: true })
  expect(wrapper.text()).toBe('本机草稿 · 尚未同步')
})

it('设置搜索只发出导航意图，导航未完成时保留搜索结果', async () => {
  const wrapper = mount(SettingsSearch, { props: { currentTab: 'common' } })
  await wrapper.get('input').setValue('密码')
  expect(wrapper.text()).toContain('账号与登录')
  await wrapper.findAll('button').find(button => button.text().includes('账号与登录'))!.trigger('click')
  expect(wrapper.emitted('navigate')).toEqual([['account']])
  expect(wrapper.get<HTMLInputElement>('input').element.value).toBe('密码')
  await wrapper.setProps({ currentTab: 'account' })
  expect(wrapper.get<HTMLInputElement>('input').element.value).toBe('')
})
