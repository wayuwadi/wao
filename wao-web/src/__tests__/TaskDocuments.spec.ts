import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { useDataStore } from '@/stores/data'
import { apiCreateDoc, fetchDocs, fetchKbs } from '@/api/board'
import TaskDocuments from '@/components/task/TaskDocuments.vue'
import { seedDataStore } from './helpers/seedDataStore'

const { push } = vi.hoisted(() => ({ push:vi.fn() }))
vi.mock('vue-router', () => ({ useRouter:() => ({ push }) }))
vi.mock('@/api/board', async original => ({ ...await original<object>(), apiCreateDoc:vi.fn(), fetchDocs:vi.fn(), fetchKbs:vi.fn() }))
enableAutoUnmount(afterEach)
beforeEach(() => { vi.clearAllMocks(); localStorage.clear(); setActivePinia(createPinia()); seedDataStore() })
function setup(subId?: string) {
  const data = useDataStore()
  const task = data.tasks[0]!
  task.docs = []; task.subs = [{ id:'child', text:'子任务', done:false, docs:[] }]
  const kb = data.kbs[0]!, folder = kb.folders[0]!
  const doc = { ...Object.values(data.docs)[0]!, id:'archive-doc', title:'归档记录', kbId:kb.id }
  vi.mocked(fetchKbs).mockResolvedValue([kb]); vi.mocked(fetchDocs).mockResolvedValue([doc])
  vi.mocked(apiCreateDoc).mockResolvedValue(doc)
  vi.spyOn(data, 'flushTask').mockResolvedValue()
  const state = vi.spyOn(data, 'taskSaveState').mockReturnValue('saved')
  vi.spyOn(data, 'patchTask').mockImplementation((id, values) => Object.assign(data.taskById(id)!, values))
  vi.spyOn(data, 'updateSub').mockImplementation((id, child, values) => Object.assign(data.subById(id, child)!, values))
  const wrapper = mount(TaskDocuments, { props:{ taskId:task.id, subId, title:'任务记录', description:'## 过程\n\n- [x] 完成\n\n![附件](/images/proof.png)' }, global:{ stubs:{ Teleport:true } } })
  const click = async (text:string) => { await wrapper.findAll('button').find(b => b.text() === text)!.trigger('click'); await flushPromises() }
  const destination = async () => { await click('归档到知识库'); await wrapper.findAll('select')[0]!.setValue(kb.id); await wrapper.findAll('select')[1]!.setValue(folder.id) }
  return { data, task, kb, folder, doc, state, wrapper, click, destination }
}
describe('清单文档关联及归档', () => {
  it('保存完整正文到所选目录，保留原详情，关联并跳转文档', async () => {
    const { task, kb, folder, wrapper, click, destination } = setup()
    const original = task.desc
    await destination(); await click('保存并关联')
    expect(apiCreateDoc).toHaveBeenCalledWith(expect.objectContaining({ kbId:kb.id, folderId:folder.id, title:'任务记录', content:expect.objectContaining({ markdown:wrapper.props('description') }) }))
    expect(task.docs).toEqual(['archive-doc']); expect(task.desc).toBe(original)
    await click('归档记录')
    expect(push).toHaveBeenCalledWith({ path:`/kb/${kb.id}`, query:{ doc:'archive-doc' } })
  })
  it('创建成功但关联失败后，重新挂载重试不会重复创建', async () => {
    const first = setup()
    await first.destination()
    first.state.mockReturnValueOnce('saved').mockReturnValueOnce('saved').mockReturnValueOnce('error')
    await first.click('保存并关联')
    expect(first.wrapper.text()).toContain('文档已保存，任务关联失败')
    first.wrapper.unmount()
    const retry = setup()
    await retry.click('查看归档'); await retry.click('保存关联')
    expect(apiCreateDoc).toHaveBeenCalledTimes(1)
    expect(retry.task.docs).toEqual(['archive-doc'])
  })
  it('关联写入失败的重试保持添加意图，不反向解除；子事项不改父任务', async () => {
    const { task, state, wrapper, click } = setup('child')
    await click('关联文档')
    state.mockReturnValueOnce('saved').mockReturnValueOnce('error')
    await wrapper.get('.document-options button').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('重试保存关联')
    await click('重试保存关联')
    expect(task.subs[0]!.docs).toEqual(['archive-doc']); expect(task.docs).toEqual([])
  })
  it('目录读取失败显示重试，任务冲突阻止创建文档', async () => {
    const { state, wrapper, click, kb, folder } = setup()
    vi.mocked(fetchKbs).mockRejectedValueOnce(new Error('offline'))
    await click('归档到知识库')
    expect(wrapper.text()).toContain('知识库读取失败')
    await click('刷新列表')
    await wrapper.findAll('select')[0]!.setValue(kb.id); await wrapper.findAll('select')[1]!.setValue(folder.id)
    state.mockReturnValue('error')
    await click('保存并关联')
    expect(apiCreateDoc).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('请先保存任务')
  })
})
