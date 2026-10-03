import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
vi.mock('@/api/board',async original=>({...await original<typeof import('@/api/board')>(),apiGetTask:vi.fn(),apiPutTask:vi.fn()}))
import { apiGetTask } from '@/api/board'
import { useDataStore } from '@/stores/data'
import { TASKS } from '@/data/fixtures'
import type { Task } from '@/types'
describe('专用任务事务与全文草稿共存',()=>{
  beforeEach(()=>{localStorage.clear();setActivePinia(createPinia());vi.clearAllMocks()})
  function setup(){const data=useDataStore();data.apiLive=true;data.tasks=[{...JSON.parse(JSON.stringify(TASKS[0]!)),editVersion:'v1'}];return{data,task:data.tasks[0]!}}
  it('排时冲突刷新干净任务的版本，下一次确认保存可正常使用新版本',async()=>{
    const {data,task}=setup();vi.mocked(apiGetTask).mockResolvedValue({...task,title:'另一窗口改名',editVersion:'v2'})
    await expect(data.runTaskOperation(task.id,async()=>{throw Object.assign(new Error('已变化'),{status:409})})).rejects.toThrow('已变化')
    expect(task.title).toBe('另一窗口改名');expect(task.editVersion).toBe('v2')
  })
  it('读取期间继续输入不能被成功操作的服务器快照覆盖',async()=>{
    const {data,task}=setup();let finish!:(result:{task:Task})=>void
    vi.mocked(apiGetTask).mockResolvedValue({...task,title:'服务器标题',editVersion:'v2'})
    const job=data.runTaskOperation(task.id,()=>new Promise<{task:Task}>(resolve=>{finish=resolve}))
    await Promise.resolve();await Promise.resolve();await Promise.resolve()
    data.patchTask(task.id,{title:'请求期间新输入'});finish({task:{...task,editVersion:'v2'}})
    await job;expect(task.title).toBe('请求期间新输入')
  })
})
