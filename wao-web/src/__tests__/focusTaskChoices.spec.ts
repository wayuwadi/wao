import { expect, it } from 'vitest'
import type { Task } from '@/types'
import type { FocusSession } from '@/api/focus'
import { focusTaskChoices, linkedFocusTask } from '@/utils/focusTaskChoices'
const task=(id:string,patch:Partial<Task>={}):Task=>({id,title:id,status:'待办',...patch} as Task)
const session=(taskId:string,subId:string,endedAt:number)=>({taskId,subId,endedAt} as FocusSession)
it('最近专注按真实父子引用排序，排除已放弃和笔记，已完成需显式开启',()=>{
  const items=[task('a'),task('b'),task('projected'),task('done',{status:'已完成'}),task('note',{workItemType:'note'}),task('abandoned',{status:'已放弃'})]
  const refs={a:{taskId:'a'},b:{taskId:'b'},projected:{taskId:'a',subId:'child'}}
  const history=[session('a','',1),session('a','child',20),session('gone','',100)]
  expect(focusTaskChoices(items,refs,history,'',false).map(t=>t.id)).toEqual(['projected','a','b'])
  expect(focusTaskChoices(items,refs,history,'done',true).map(t=>t.id)).toEqual(['done'])
  expect(linkedFocusTask(history[1]!,refs)).toBe('projected')
  expect(linkedFocusTask(history[2]!,refs)).toBeUndefined()
})
it('搜索名称、描述和编号，不改变来源任务或历史数组',()=>{
  const items=[task('a',{title:'整理报告',desc:'Review notes',number:123}),task('b')]
  expect(focusTaskChoices(items,{},[],' review ',false).map(t=>t.id)).toEqual(['a'])
  expect(focusTaskChoices(items,{},[],'123',false).map(t=>t.id)).toEqual(['a'])
  expect(items.map(t=>t.id)).toEqual(['a','b'])
})
