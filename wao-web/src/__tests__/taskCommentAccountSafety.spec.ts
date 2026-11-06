import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { apiAddTaskComment, apiDeleteTaskComment, apiGetTask, apiPutTask, apiUpdateTaskComment, apiUploadFile } from '@/api/board'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { seedDataStore } from './helpers/seedDataStore'
import type { Comment, FileItem, Task } from '@/types'
import TaskComments from '@/components/task/TaskComments.vue'

vi.mock('@/api/board', async original => ({
  ...await original<typeof import('@/api/board')>(),
  apiAddTaskComment: vi.fn(), apiUpdateTaskComment:vi.fn(), apiDeleteTaskComment:vi.fn(), apiGetTask:vi.fn(), apiPutTask: vi.fn(), apiUploadFile: vi.fn(),
}))
const member = (id:string) => ({ accountId:id, member:{ id, name:id, initial:id, color:'', role:'' }, identities:[], createdAt:'' })
beforeEach(() => {
  vi.resetAllMocks(); localStorage.clear(); sessionStorage.clear(); setActivePinia(createPinia())
  useSessionStore().accept(member('first'))
  const data = seedDataStore(); data.apiLive = true
})
describe('评论与附件请求的账号隔离', () => {
  it('旧评论入口成功后刷新任务版本，后续保存不携带旧版本', async () => {
    const data=useDataStore(), task=data.tasks[0]!
    task.editVersion='before';task.comments=[]
    const comment={id:'comment-1',who:'first',time:'2026-11-06T10:00:00+08:00',text:'一条评论'}
    vi.mocked(apiPutTask).mockResolvedValue(JSON.parse(JSON.stringify(task)))
    vi.mocked(apiAddTaskComment).mockResolvedValue(comment)
    vi.mocked(apiGetTask).mockResolvedValue({...JSON.parse(JSON.stringify(task)),comments:[comment],editVersion:'after'})
    expect(await data.addTaskComment(task.id,comment.text)).toBe(true)
    expect(task.editVersion).toBe('after')
    expect(task.comments).toEqual([comment])
  })
  it('侧栏评论写入成功后使用服务器新版本保存后续编辑', async () => {
    const data = useDataStore(), task = data.tasks[0]!
    task.editVersion = 'before-comment'
    vi.mocked(apiPutTask).mockImplementation(async (_id, snapshot) => ({
      ...JSON.parse(JSON.stringify(snapshot)), editVersion: snapshot.editVersion === 'before-comment' ? 'after-comment' : 'after-edit',
    }))
    const wrapper = mount(TaskComments, { props: { taskId: task.id } })
    await wrapper.get('[aria-label="评论内容"]').setValue('一条评论')
    await wrapper.get('[aria-label="评论内容"]').trigger('keydown', { key: 'Enter' }); await flushPromises()
    expect(task.comments.filter(comment => comment.text === '一条评论')).toHaveLength(1)
    expect(task.editVersion).toBe('after-comment')
    data.patchTask(task.id, { title: '评论后的编辑' })
    await data.flushTask(task.id)
    expect(vi.mocked(apiPutTask).mock.calls[1]?.[1].editVersion).toBe('after-comment')
    expect(task.editVersion).toBe('after-edit')
    wrapper.unmount()
  })

  it('服务端已保存但响应丢失时，重试保留同一条评论', async () => {
    const data = useDataStore(), task = data.tasks[0]!
    task.editVersion = 'before-comment'
    task.comments = []
    let committed: Task | undefined
    vi.mocked(apiPutTask).mockImplementationOnce(async (_id, snapshot) => {
      committed = { ...JSON.parse(JSON.stringify(snapshot)), editVersion: 'committed-version' }
      throw new Error('响应丢失')
    }).mockImplementationOnce(async (_id, snapshot) => {
      expect(snapshot.comments).toHaveLength(1)
      return committed!
    })
    const wrapper = mount(TaskComments, { props: { taskId: task.id } })
    await wrapper.get('[aria-label="评论内容"]').setValue('只保存一次')
    await wrapper.get('[aria-label="评论内容"]').trigger('keydown', { key: 'Enter' }); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('重试保存')
    expect(committed?.comments.map(comment => comment.text)).toEqual(['只保存一次'])
    await wrapper.findAll('button').find(button => button.text() === '重试保存任务')!.trigger('click'); await flushPromises()
    expect(vi.mocked(apiPutTask)).toHaveBeenCalledTimes(2)
    expect(task.comments.map(comment => comment.text)).toEqual(['只保存一次'])
    expect(task.editVersion).toBe('committed-version')
    wrapper.unmount()
  })

  it('等待任务保存时切换账号，不以新账号发送旧评论', async () => {
    const data=useDataStore(), task=data.tasks[0]!
    let finish!: (value:Task) => void
    vi.mocked(apiPutTask).mockImplementation(() => new Promise(resolve => { finish=resolve }))
    const request=data.addTaskComment(task.id,'原账号的评论')
    useSessionStore().accept(member('second'))
    finish(JSON.parse(JSON.stringify(task)))
    expect(await request).toBe(false)
    expect(apiAddTaskComment).not.toHaveBeenCalled()
  })
  it('旧账号评论已成功时返回成功，但不写入切换后的任务列表', async () => {
    const data=useDataStore(), task=data.tasks[0]!, initial=task.comments.length
    vi.mocked(apiPutTask).mockResolvedValue(JSON.parse(JSON.stringify(task)))
    let finish!: (value:Comment) => void
    vi.mocked(apiAddTaskComment).mockImplementation(() => new Promise(resolve => { finish=resolve }))
    const request=data.addTaskComment(task.id,'已经发送')
    await flushPromises()
    useSessionStore().accept(member('second'))
    finish({id:'comment-1',who:'first',time:'2026-11-06T10:00:00+08:00',text:'已经发送'})
    expect(await request).toBe(true)
    expect(task.comments).toHaveLength(initial)
  })
  it('上传完成前切换账号，不把旧账号的文件加入新账号列表', async () => {
    const data=useDataStore()
    let finish!: (value:FileItem) => void
    vi.mocked(apiUploadFile).mockImplementation(() => new Promise(resolve => { finish=resolve }))
    const request=data.uploadFile(new File(['text'],'test.txt'))
    useSessionStore().accept(member('second'))
    finish({...data.files[0]!,id:'new-file'})
    expect(await request).toBeNull()
    expect(data.files.some(file=>file.id==='new-file')).toBe(false)
  })
  it('账号变化而成员标识相同时，也不会提交先前账号的评论', async () => {
    const data=useDataStore(), task=data.tasks[0]!
    let finish!: (value:Task) => void
    vi.mocked(apiPutTask).mockImplementation(() => new Promise(resolve => { finish=resolve }))
    const request=data.addTaskComment(task.id,'先前账号的草稿')
    useSessionStore().accept({ ...member('first'), accountId:'another-account' })
    finish(JSON.parse(JSON.stringify(task)))
    expect(await request).toBe(false)
    expect(apiAddTaskComment).not.toHaveBeenCalled()
  })
  it('评论修改失败不会覆盖请求期间出现的新内容', async () => {
    const data=useDataStore(), task=data.tasks[0]!
    task.comments=[{id:'c1',who:'first',time:'今天',text:'原文'}]
    let fail!: (reason:Error) => void
    vi.mocked(apiUpdateTaskComment).mockImplementation(()=>new Promise((_,reject)=>{fail=reject}))
    const request=data.updateTaskComment(task.id,0,'本次编辑')
    task.comments[0]!.text='请求期间的新内容'
    fail(new Error('离线'))
    expect(await request).toBe(false)
    expect(task.comments[0]!.text).toBe('请求期间的新内容')
  })
  it('评论删除失败会恢复原文，但不会重复插入刷新后已恢复的评论', async () => {
    const data=useDataStore(), task=data.tasks[0]!
    task.comments=[{id:'c1',who:'first',time:'今天',text:'原文'}]
    let fail!: (reason:Error) => void
    vi.mocked(apiDeleteTaskComment).mockImplementation(()=>new Promise((_,reject)=>{fail=reject}))
    const request=data.deleteTaskComment(task.id,0)
    task.comments.push({id:'c1',who:'first',time:'今天',text:'刷新后的原文'})
    fail(new Error('离线'))
    expect(await request).toBe(false)
    expect(task.comments.map(comment=>comment.text)).toEqual(['刷新后的原文'])
  })
})
