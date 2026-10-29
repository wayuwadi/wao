import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { seedDataStore } from './helpers/seedDataStore'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { fetchProjects, fetchTasks } from '@/api/board'
import type { Task } from '@/types'
vi.mock('@/api/board',async original=>({...await original<typeof import('@/api/board')>(),fetchProjects:vi.fn(),fetchTasks:vi.fn(),apiPutTask:vi.fn(async(_id,task)=>({...task,editVersion:'saved-version'}))}))
beforeEach(()=>{
  vi.useFakeTimers();vi.setSystemTime(new Date('2026-10-29T12:00:00Z'))
  localStorage.clear();setActivePinia(createPinia())
  useSessionStore().accept({accountId:'first',member:{id:'wg',name:'测试',initial:'测',role:'',color:''},identities:[],createdAt:''})
})
afterEach(()=>{useDataStore().$dispose();vi.useRealTimers()})
it('迟到的旧快照不能覆盖已写透子项；之后的新快照无需再等20秒',async()=>{
  const data=seedDataStore(); data.apiLive=true
  const task=data.tasks[0]!;task.subs=[{id:'child',text:'子任务',done:false,pr:'P3'}]
  const old=JSON.parse(JSON.stringify(task)) as Task
  vi.mocked(fetchProjects).mockResolvedValue([...data.projects])
  let resolve!: (tasks:Task[])=>void
  vi.mocked(fetchTasks).mockImplementationOnce(()=>new Promise(done=>{resolve=done}))
  const syncing=data.syncFromServer()
  await vi.advanceTimersByTimeAsync(10)
  data.updateSub(task.id,'child',{pr:'P0'})
  await data.flushTask(task.id)
  resolve([old]);await syncing
  expect(data.subById(task.id,'child')?.pr).toBe('P0')
  await vi.advanceTimersByTimeAsync(10)
  const latest={...task,subs:[{...task.subs[0]!,pr:'P1' as const}]}
  vi.mocked(fetchTasks).mockResolvedValueOnce([latest])
  await data.syncFromServer()
  expect(data.subById(task.id,'child')?.pr).toBe('P1')
})
it('账号切换期间旧请求返回也不能混入另一账号',async()=>{
  const data=seedDataStore();data.apiLive=true
  vi.mocked(fetchProjects).mockResolvedValue([...data.projects])
  let resolve!: (tasks:Task[])=>void
  vi.mocked(fetchTasks).mockImplementationOnce(()=>new Promise(done=>{resolve=done}))
  const old={...data.tasks[0]!,id:'private-old-account'}
  const syncing=data.syncFromServer()
  useSessionStore().accept({accountId:'second',member:{id:'second',name:'另一成员',initial:'测',role:'',color:''},identities:[],createdAt:''})
  data.tasks=[]
  resolve([old]);await syncing
  expect(data.tasks).toHaveLength(0)
})
