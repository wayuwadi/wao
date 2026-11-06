import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TaskComments from '@/components/task/TaskComments.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { seedDataStore } from './helpers/seedDataStore'

enableAutoUnmount(afterEach)
let serial = 0
beforeEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  setActivePinia(createPinia())
  useSessionStore().accept({ accountId:'test', member:{ id:'me', name:'本人', initial:'本', role:'', color:'' }, identities:[], createdAt:'' })
  seedDataStore()
})
function setup() {
  const data = useDataStore()
  const id = `comment-test-${++serial}`
  data.tasks = [{ ...data.tasks[0]!, id, comments:[], subs:[{ id:'sub', text:'子任务', done:false, comments:[] }] }]
  const mountFor = (subId?: string) => mount(TaskComments, { props:{ taskId:id, subId } })
  return { data, id, mountFor }
}
describe('任务侧栏评论', () => {
  it('输入法确认和 Shift+Enter 不发送，保存失败后重试不重复追加', async () => {
    const { data, id, mountFor } = setup()
    let state: 'error' | 'saved' = 'error'
    vi.spyOn(data, 'taskSaveState').mockImplementation(() => state)
    const flush = vi.spyOn(data, 'flushTask').mockResolvedValue()
    const update = vi.spyOn(data, 'patchTask')
    const w = mountFor()
    const input = w.get('[aria-label="评论内容"]')
    await input.setValue('第一行\n第二行')
    await input.trigger('keydown', { key:'Enter', isComposing:true })
    await input.trigger('keydown', { key:'Enter', shiftKey:true })
    expect(update).not.toHaveBeenCalled()
    await input.trigger('keydown', { key:'Enter' }); await flushPromises()
    expect(data.taskById(id)?.comments.map(comment => comment.text)).toEqual(['第一行\n第二行'])
    expect(input.element).toHaveProperty('value', '')
    expect(w.get('[role="alert"]').text()).toContain('重试保存')
    state = 'saved'
    await w.findAll('button').find(button => button.text() === '重试保存任务')!.trigger('click'); await flushPromises()
    expect(update).toHaveBeenCalledTimes(1)
    expect(flush).toHaveBeenCalledTimes(2)
    expect(data.taskById(id)?.comments).toHaveLength(1)
  })

  it('切走再切回时保留草稿并锁定在途请求，避免重复发送', async () => {
    const { data, id, mountFor } = setup()
    let finish!: () => void
    vi.spyOn(data, 'taskSaveState').mockReturnValue('saved')
    const flush = vi.spyOn(data, 'flushTask').mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const update = vi.spyOn(data, 'patchTask')
    const first = mountFor()
    await first.get('[aria-label="评论内容"]').setValue('只发送一次')
    await first.get('[aria-label="评论内容"]').trigger('keydown', { key:'Enter' })
    first.unmount()
    const reopened = mountFor()
    expect(data.taskById(id)?.comments.map(comment => comment.text)).toEqual(['只发送一次'])
    expect(reopened.get('[aria-label="评论内容"]').attributes('disabled')).toBeDefined()
    finish(); await flushPromises()
    expect(update).toHaveBeenCalledTimes(1)
    expect(flush).toHaveBeenCalledTimes(1)
    expect(reopened.get('[aria-label="评论内容"]').element).toHaveProperty('value', '')
  })

  it('子任务评论写入对应子项，保存失败后重试任务而不重复添加', async () => {
    const { data, id, mountFor } = setup()
    let state: 'error' | 'saved' = 'error'
    vi.spyOn(data, 'taskSaveState').mockImplementation(() => state)
    const flush = vi.spyOn(data, 'flushTask').mockResolvedValue()
    const update = vi.spyOn(data, 'updateSub')
    const w = mountFor('sub')
    await w.get('[aria-label="评论内容"]').setValue('子项的评论')
    await w.get('[aria-label="评论内容"]').trigger('keydown', { key:'Enter' }); await flushPromises()
    expect(data.subById(id, 'sub')?.comments?.map(item => item.text)).toEqual(['子项的评论'])
    expect(data.taskById(id)?.comments).toEqual([])
    expect(w.get('[role="alert"]').text()).toContain('重试保存')
    state = 'saved'
    await w.findAll('button').find(button => button.text() === '重试保存任务')!.trigger('click'); await flushPromises()
    expect(update).toHaveBeenCalledTimes(1)
    expect(flush).toHaveBeenCalledTimes(2)
    expect(w.find('[role="alert"]').exists()).toBe(false)
  })

  it('仅本人评论可编辑，编辑与删除定位到该子任务', async () => {
    const { data, id, mountFor } = setup()
    data.subById(id, 'sub')!.comments = [
      { id:'mine', who:'me', time:'2026-11-06T10:00:00+08:00', text:'原文' },
      { id:'theirs', who:'other', time:'2026-11-06T10:01:00+08:00', text:'他人评论' },
    ]
    vi.spyOn(data, 'taskSaveState').mockReturnValue('saved')
    vi.spyOn(data, 'flushTask').mockResolvedValue()
    const w = mountFor('sub')
    expect(w.findAll('[aria-label="修改评论"]')).toHaveLength(1)
    await w.get('[aria-label="修改评论"]').trigger('click')
    await w.get('[aria-label="修改评论内容"]').setValue('修改后')
    await w.findAll('button').find(button => button.text() === '保存')!.trigger('click'); await flushPromises()
    expect(data.subById(id, 'sub')?.comments?.[0]?.text).toBe('修改后')
    await w.get('[aria-label="删除评论"]').trigger('click'); await flushPromises()
    const confirm = [...document.querySelectorAll<HTMLButtonElement>('button')].find(button => button.textContent?.trim() === '确认删除')!
    confirm.click(); await flushPromises()
    expect(data.subById(id, 'sub')?.comments?.map(item => item.id)).toEqual(['theirs'])
  })

  it('编辑期间评论被其他地方修改或删除，保留输入且不覆盖新内容', async () => {
    const { data, id, mountFor } = setup()
    data.subById(id, 'sub')!.comments = [{ id:'mine', who:'me', time:'2026-11-06T10:00:00+08:00', text:'原文' }]
    const update = vi.spyOn(data, 'updateSub')
    const w = mountFor('sub')
    await w.get('[aria-label="修改评论"]').trigger('click')
    await w.get('[aria-label="修改评论内容"]').setValue('尚未保存')
    data.subById(id, 'sub')!.comments![0]!.text = '别处的新内容'
    await w.vm.$nextTick()
    expect(w.get('[aria-label="修改评论内容"]').element).toHaveProperty('value', '尚未保存')
    await w.findAll('button').find(button => button.text() === '保存')!.trigger('click')
    expect(w.get('[role="alert"]').text()).toContain('已变化')
    expect(update).not.toHaveBeenCalled()
    data.subById(id, 'sub')!.comments = []
    await w.vm.$nextTick()
    expect(w.get('[aria-label="修改评论内容"]').element).toHaveProperty('value', '尚未保存')
    expect(w.text()).toContain('编辑草稿仍保留')
  })

  it('评论编辑无法写入本机草稿且同步失败时保留编辑框并提示不要关闭', async () => {
    const { data, id, mountFor } = setup()
    data.taskById(id)!.comments=[{id:'mine',who:'me',time:'2026-11-06T10:00:00+08:00',text:'原文'}]
    vi.spyOn(data, 'taskSaveState').mockReturnValue('error')
    vi.spyOn(data, 'flushTask').mockResolvedValue()
    const w=mountFor()
    await w.get('[aria-label="修改评论"]').trigger('click')
    await w.get('[aria-label="修改评论内容"]').setValue('本机内容')
    const storage=vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('quota')})
    await w.findAll('button').find(button=>button.text()==='保存')!.trigger('click');await flushPromises()
    expect(w.get('[aria-label="修改评论内容"]').element).toHaveProperty('value','本机内容')
    expect(w.get('[role="alert"]').text()).toContain('仅保存在当前页面')
    storage.mockRestore()
  })

  it('版本冲突时引导查看保存问题而不是反复重试', async () => {
    const { data, id, mountFor } = setup()
    data.taskConflicts[id]=true
    vi.spyOn(data, 'taskSaveState').mockReturnValue('error')
    vi.spyOn(data, 'flushTask').mockResolvedValue()
    const w=mountFor()
    await w.get('[aria-label="评论内容"]').setValue('冲突时的评论')
    await w.get('[aria-label="评论内容"]').trigger('keydown',{key:'Enter'});await flushPromises()
    expect(w.get('[role="alert"]').text()).toContain('别处更新')
    await w.findAll('button').find(button=>button.text()==='查看保存问题')!.trigger('click')
    expect(useUiStore().openTaskId).toBe(id)
  })

  it('冲突且本机草稿不可写时提示修改仅保存在当前页面', async () => {
    const { data, id, mountFor } = setup()
    data.taskConflicts[id]=true
    vi.spyOn(data,'taskSaveState').mockReturnValue('error')
    vi.spyOn(data,'flushTask').mockResolvedValue()
    const w=mountFor()
    await w.get('[aria-label="评论内容"]').setValue('仅在内存中的评论')
    const storage=vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('quota')})
    await w.get('[aria-label="评论内容"]').trigger('keydown',{key:'Enter'});await flushPromises()
    expect(w.get('[role="alert"]').text()).toContain('仅保存在当前页面')
    expect(w.get('[role="alert"]').text()).toContain('别处更新')
    storage.mockRestore()
  })

  it('删除保存失败后关闭确认框，侧栏仍显示待保存状态', async () => {
    const { data, id, mountFor } = setup()
    data.taskById(id)!.comments=[{id:'mine',who:'me',time:'2026-11-06T10:00:00+08:00',text:'待删除'}]
    let state:'error'|'saved'='error'
    vi.spyOn(data,'taskSaveState').mockImplementation(()=>state)
    vi.spyOn(data,'flushTask').mockResolvedValue()
    const w=mountFor()
    await w.get('[aria-label="删除评论"]').trigger('click');await flushPromises()
    const findDialogButton=(text:string)=>[...document.querySelectorAll<HTMLButtonElement>('.confirmation-actions button')].find(button=>button.textContent?.trim()===text)!
    findDialogButton('确认删除').click();await flushPromises()
    expect(data.taskById(id)!.comments).toHaveLength(0)
    findDialogButton('关闭').click();await flushPromises()
    expect(w.get('[role="alert"]').text()).toContain('删除已保留在任务草稿中')
    state='saved'
    await w.findAll('button').find(button=>button.text()==='重试保存任务')!.trigger('click');await flushPromises()
    expect(w.find('[role="alert"]').exists()).toBe(false)
    expect(data.taskById(id)!.comments).toHaveLength(0)
  })
})
