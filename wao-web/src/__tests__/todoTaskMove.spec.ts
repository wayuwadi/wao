import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { useTodoTaskMove } from '@/composables/todoTaskMove'
import { projectWorkItems } from '@/utils/projectWorkItems'
import { seedDataStore } from './helpers/seedDataStore'
import { savePreference } from '@/api/favorites'
vi.mock('@/api/favorites', async original => ({ ...await original<object>(), fetchPreference: vi.fn(async()=>''), savePreference: vi.fn(async()=>{}) }))
beforeEach(()=>{ localStorage.clear();setActivePinia(createPinia());seedDataStore();useSessionStore().accept({accountId:'move-test',member:{id:'wg',name:'测试',initial:'测',role:'',color:''},identities:[],createdAt:''});vi.clearAllMocks() })
function setup() {
 const data=useDataStore(), base=data.tasks[0]!
 data.tasks=[{...base,id:'a',parentTaskId:null,subs:[{id:'s',text:'子项',done:false}]},{...base,id:'b',parentTaskId:null,subs:[]}]
 const mover=useTodoTaskMove(()=>projectWorkItems(data.tasks),()=> 'test-list')
 return {data,mover}
}
it('同级拖拽保存个人顺序，不写任务关系；失败保留草稿供重试',async()=>{
 const {data,mover}=setup(), relation=vi.spyOn(data,'moveTask')
 vi.mocked(savePreference).mockRejectedValueOnce(new Error('offline'))
 await mover.move('b','a','before')
 const order=useTaskOrderStore()
 expect(order.sort('test-list',data.tasks,t=>t.id).map(t=>t.id)).toEqual(['b','a'])
 expect(relation).not.toHaveBeenCalled()
 expect(order.state('test-list').dirty).toBe(true)
 expect(order.state('test-list').error).toBe('offline')
 await order.retry('test-list')
 expect(order.state('test-list').dirty).toBe(false)
})
it('中间嵌套与移到顶层走真实任务 ID，拒绝循环和跨清单',async()=>{
 const {data,mover}=setup()
 const relation=vi.spyOn(data,'moveTask').mockImplementation(async(id,parent)=>{data.taskById(id)!.parentTaskId=parent})
 await mover.move('b','a','inside')
 expect(relation).toHaveBeenLastCalledWith('b','a')
 await mover.move('a','b','inside')
 expect(mover.error.value).toContain('自己的子任务')
 expect(relation).toHaveBeenCalledTimes(1)
 await mover.move('b',null,'inside')
 expect(relation).toHaveBeenLastCalledWith('b',null)
 data.taskById('b')!.project='other-list'
 await mover.move('a','b','inside')
 expect(mover.error.value).toContain('同一清单')
})
it('内嵌子项跨父任务移动传入子项 ID，不传投影 ID；失败保留源项',async()=>{
 const {data,mover}=setup()
 const move=vi.spyOn(data,'moveSubtask').mockRejectedValue(new Error('移动未保存'))
 await mover.move('sub:a:s','b','inside')
 expect(move).toHaveBeenCalledWith('a','s','b',null)
 expect(mover.error.value).toBe('移动未保存')
 expect(data.taskById('a')!.subs[0]!.id).toBe('s')
})
it('同一子树的子项上下排序也持久化，且拒绝拖进后代',async()=>{
 const {data,mover}=setup()
 data.taskById('a')!.subs.push({id:'s2',text:'第二项',done:false},{id:'s3',text:'后代',done:false,parentId:'s'})
 await mover.move('sub:a:s2','sub:a:s','before')
 const order=useTaskOrderStore(), items=projectWorkItems(data.tasks).items
 expect(order.sort('test-list',items,t=>t.id).findIndex(t=>t.id==='sub:a:s2')).toBeLessThan(order.sort('test-list',items,t=>t.id).findIndex(t=>t.id==='sub:a:s'))
 await mover.move('sub:a:s','sub:a:s3','inside')
 expect(mover.error.value).toContain('自己的子任务')
})
