import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TodoQuickList from '@/components/todo/TodoQuickList.vue'

beforeEach(() => localStorage.clear())
const add = async (w: ReturnType<typeof mount>, text: string) => {
  await w.get('input[aria-label="快速添加任务"]').setValue(text)
  await w.get('form.capture').trigger('submit')
}
describe('本地随手清单', () => {
  it('持久保存层级、正文与草稿，并隔离账号', async () => {
    const w = mount(TodoQuickList, { props: { userId: 'a' } })
    await add(w, '整理资料')
    await w.get('[aria-label="任务内容"]').setValue('记录进展')
    await w.get('input[aria-label="添加子任务"]').setValue('查找来源')
    await w.get('form.child-capture').trigger('submit')
    expect(w.findAll('.task-row')).toHaveLength(2)
    await w.get('input[aria-label="快速添加任务"]').setValue('未提交草稿')
    w.unmount()
    const restored = mount(TodoQuickList, { props: { userId: 'a' } })
    expect(restored.findAll('.task-row')).toHaveLength(2)
    expect((restored.get('[aria-label="任务内容"]').element as HTMLTextAreaElement).value).toBe(
      '记录进展',
    )
    expect(
      (restored.get('input[aria-label="快速添加任务"]').element as HTMLInputElement).value,
    ).toBe('未提交草稿')
    const other = mount(TodoQuickList, { props: { userId: 'b' } })
    expect(other.findAll('.task-row')).toHaveLength(0)
    restored.unmount()
    other.unmount()
  })
  it('子项未完成时阻止父项完成，重新打开子项恢复祖先', async () => {
    const w = mount(TodoQuickList, { props: { userId: 'a' } })
    await add(w, '父项')
    await w.get('input[aria-label="添加子任务"]').setValue('子项')
    await w.get('form.child-capture').trigger('submit')
    expect(w.get('input[aria-label="完成 父项"]').attributes('disabled')).toBeDefined()
    await w.get('.show-done input').setValue(true)
    await w.get('input[aria-label="完成 子项"]').setValue(true)
    await w.get('input[aria-label="完成 父项"]').setValue(true)
    await w.get('input[aria-label="完成 子项"]').setValue(false)
    expect((w.get('input[aria-label="完成 父项"]').element as HTMLInputElement).checked).toBe(false)
    w.unmount()
  })
  it('存储损坏不覆盖；其他标签修改后保留输入并阻止覆盖', async () => {
    localStorage.setItem('wao:quick-list:v1:a', '{broken')
    const broken = mount(TodoQuickList, { props: { userId: 'a' } })
    expect(broken.text()).toContain('读取失败')
    expect(localStorage.getItem('wao:quick-list:v1:a')).toBe('{broken')
    broken.unmount()
    const w = mount(TodoQuickList, { props: { userId: 'b' } })
    await add(w, '原任务')
    localStorage.setItem('wao:quick-list:v1:b', 'other-tab')
    await w.get('[aria-label="任务内容"]').setValue('未覆盖的本页输入')
    expect(w.text()).toContain('另一页面已修改')
    expect(localStorage.getItem('wao:quick-list:v1:b')).toBe('other-tab')
    expect((w.get('[aria-label="任务内容"]').element as HTMLTextAreaElement).value).toBe(
      '未覆盖的本页输入',
    )
    w.unmount()
  })
  it('存储失败保留输入，恢复后可重试保存', async () => {
    const w = mount(TodoQuickList, { props: { userId: 'a' } })
    await add(w, '需要保存的任务')
    const failing = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota')
    })
    await w.get('[aria-label="任务内容"]').setValue('不能丢的内容')
    expect(w.text()).toContain('保存失败')
    failing.mockRestore()
    await w
      .findAll('button')
      .find((b) => b.text() === '重试保存')!
      .trigger('click')
    expect(w.text()).toContain('已保存到本机')
    expect(localStorage.getItem('wao:quick-list:v1:a')).toContain('不能丢的内容')
    w.unmount()
  })
  it('切换任务时各自保留子任务输入', async () => {
    const w = mount(TodoQuickList, { props: { userId: 'a' } })
    await add(w, '甲')
    await w.get('[aria-label="添加子任务"]').setValue('甲的草稿')
    await add(w, '乙')
    expect((w.get('[aria-label="添加子任务"]').element as HTMLInputElement).value).toBe('')
    await w.findAll('.row-title')[0]!.trigger('click')
    expect((w.get('[aria-label="添加子任务"]').element as HTMLInputElement).value).toBe('甲的草稿')
    w.unmount()
  })
})
