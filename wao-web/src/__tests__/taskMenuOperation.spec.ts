import {beforeEach,expect,it,vi} from 'vitest'
import {createPinia,setActivePinia} from 'pinia'
import {useDataStore} from '@/stores/data'
import {useSessionStore} from '@/stores/session'
import {TASKS} from '@/data/fixtures'
import {saveTaskMenuOperation} from '@/api/taskMenu'
import {apiGetTask,apiPutTask} from '@/api/board'
vi.mock('@/api/taskMenu',()=>({saveTaskMenuOperation:vi.fn()}))
vi.mock('@/api/board',async original=>({...await original<typeof import('@/api/board')>(),apiGetTask:vi.fn(),apiPutTask:vi.fn()}))
vi.mock('@/api/taskLabels',()=>({fetchTaskLabels:vi.fn(async()=>[])}))
function login(account='a',member='m'){useSessionStore().accept({accountId:account,member:{id:member,name:'测试',initial:'测',color:'',role:''},identities:[],createdAt:''})}
beforeEach(()=>{localStorage.clear();sessionStorage.clear();setActivePinia(createPinia());vi.clearAllMocks();vi.mocked(apiPutTask).mockImplementation(async(_id,t)=>JSON.parse(JSON.stringify(t)));login()})
function setup(){const data=useDataStore();data.apiLive=true;data.tasks=[{...structuredClone(TASKS[0]!),id:'source',owner:'m',editVersion:'v1',subs:[]}];return data}
it('响应丢失后重试沿用相同操作和版本，成功只插入一次副本',async()=>{
 const data=setup(),copy={...JSON.parse(JSON.stringify(data.tasks[0]!)),id:'copy',editVersion:'copy-v1'}
 vi.mocked(saveTaskMenuOperation).mockRejectedValueOnce(new Error('network')).mockResolvedValue({selectedId:'copy',tasks:[copy]})
 vi.mocked(apiGetTask).mockResolvedValue(copy)
 await expect(data.taskMenuOperation('source',undefined,'duplicate')).rejects.toThrow('network')
 await data.taskMenuOperation('source',undefined,'duplicate')
 const calls=vi.mocked(saveTaskMenuOperation).mock.calls
 expect(calls[0]![1]).toBe(calls[1]![1]);expect(calls[0]![3]).toEqual(calls[1]![3]);expect(data.tasks.filter(t=>t.id==='copy')).toHaveLength(1)
 expect(Object.keys(localStorage).filter(key=>key.startsWith('wao-task-menu:'))).toHaveLength(0)
})
it('旧操作未确认时先恢复原结果，不执行后来选择的相反操作',async()=>{
 const data=setup(),note={...JSON.parse(JSON.stringify(data.tasks[0]!)),workItemType:'note' as const,editVersion:'v2'}
 vi.mocked(saveTaskMenuOperation).mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({selectedId:'source',tasks:[note]});vi.mocked(apiGetTask).mockResolvedValue(note)
 await expect(data.taskMenuOperation('source',undefined,'note')).rejects.toThrow('timeout')
 await expect(data.taskMenuOperation('source',undefined,'task')).rejects.toThrow('上次操作已确认')
 expect(vi.mocked(saveTaskMenuOperation).mock.calls[1]![3].action).toBe('note');expect(data.taskById('source')?.workItemType).toBe('note')
})
it('切换账号后迟到响应不把原账号副本插入新账号',async()=>{
 const data=setup();let finish!:(v:Awaited<ReturnType<typeof saveTaskMenuOperation>>)=>void
 vi.mocked(saveTaskMenuOperation).mockImplementation(()=>new Promise(resolve=>{finish=resolve}))
 const job=data.taskMenuOperation('source',undefined,'duplicate')
 await vi.waitFor(()=>expect(saveTaskMenuOperation).toHaveBeenCalled())
 login('other','other-member');finish({selectedId:'copy',tasks:[{...structuredClone(TASKS[0]!),id:'copy'}]})
 await expect(job).rejects.toThrow('账号已切换');expect(data.taskById('copy')).toBeUndefined()
})
it('菜单写入期间的新正文不会被回执覆盖',async()=>{
 const data=setup();let finish!:(v:Awaited<ReturnType<typeof saveTaskMenuOperation>>)=>void
 const abandoned={...JSON.parse(JSON.stringify(data.tasks[0]!)),status:'已放弃' as const,editVersion:'v2'}
 vi.mocked(saveTaskMenuOperation).mockImplementation(()=>new Promise(resolve=>{finish=resolve}));vi.mocked(apiGetTask).mockResolvedValue(abandoned)
 const job=data.taskMenuOperation('source',undefined,'abandon')
 await vi.waitFor(()=>expect(saveTaskMenuOperation).toHaveBeenCalled())
 data.patchTask('source',{desc:'等待期间继续输入的正文'})
 finish({selectedId:'source',tasks:[abandoned]});await job
 expect(data.taskById('source')?.desc).toBe('等待期间继续输入的正文');expect(data.taskById('source')?.status).toBe('已放弃')
 await data.flushTask('source')
 const calls=vi.mocked(apiPutTask).mock.calls
 expect(calls[calls.length-1]?.[1].desc).toBe('等待期间继续输入的正文')
})
