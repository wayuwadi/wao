<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { useRouter } from 'vue-router'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import TaskQuickActionDialog from './TaskQuickActionDialog.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useProjectItemActions } from '@/composables/projectItemActions'
import { PROJECT_PRIORITY_COLORS, PROJECT_PRIORITY_LABELS } from '@/utils/projectStatusPresentation'
import { shareUrl } from '@/utils/share'
import { copyText } from '@/utils/clipboard'
import { shiftDay } from '@/utils/taskDeadline'
import { todayIso } from '@/utils/time'
import type { Task, Priority } from '@/types'
import type { ContextAction } from '@/types/contextAction'
import type { ProjectItemRefs } from '@/utils/projectWorkItems'
const props=defineProps<{itemRefs?:ProjectItemRefs}>()
const emit=defineEmits<{ dateChanging:[id:string] }>()
const data=useDataStore(), session=useSessionStore(), router=useRouter(), ui=useUiStore()
const actions=useProjectItemActions(()=>props.itemRefs)
const menu=ref<InstanceType<typeof ContextActionMenu>>()
const formId=useId()
const target=ref<Task>(), mode=ref<''|'date'|'move'|'parent'|'delete'|'error'>('')
const date=ref(''), destination=ref(''), saving=ref(false), error=ref('')
const quick=ref<{taskId:string;subId?:string;mode:'tags'|'child'|'priority'}>()
const source=ref<{taskId:string;subId?:string}>({taskId:''})
const descendants = (id:string) => {
 const ids=new Set([id]); let changed=true
 while(changed){changed=false;for(const task of data.tasks)if(task.parentTaskId&&ids.has(task.parentTaskId)&&!ids.has(task.id)){ids.add(task.id);changed=true}}
 return ids
}
const parents=computed(()=>{
 const task=data.taskById(source.value.taskId), excluded=descendants(source.value.taskId)
 return data.tasks.filter(t=>t.project===task?.project && (source.value.subId || !excluded.has(t.id)))
})
function movable(task:Task){return !actions.isSub(task.id)&&!task.parentTaskId&&!data.tasks.some(t=>t.parentTaskId===task.id)}
function close(){menu.value?.close()}
watch(()=>session.userId,()=>{close();mode.value='';quick.value=undefined;target.value=undefined},{flush:'sync'})
function dialog(value:'date'|'move'|'parent'|'delete') {
 if(!target.value)return
 mode.value=value;error.value='';date.value=target.value.due||todayIso()
 destination.value=value==='move'?target.value.project:value==='parent'?(target.value.parentTaskId??''):''
}
async function run(change:()=>void|Promise<void>, dateChange=false) {
 const task=target.value, member=session.userId
 if(!task||saving.value)return
 const ref=actions.source(task.id)
 saving.value=true;error.value=''
 try {
  if(actions.busy(task.id))throw new Error('任务正在保存或移动，请稍后重试')
  if(!data.taskById(ref.taskId)||(ref.subId&&!data.subById(ref.taskId,ref.subId)))throw new Error('任务已不存在')
  if(data.taskSaveState(ref.taskId)==='error')throw new Error('请先在任务详情中处理已有的保存问题')
  if(dateChange)emit('dateChanging',task.id)
  await change()
  if(member!==session.userId)return
  await data.flushTask(ref.taskId)
  if(data.taskSaveState(ref.taskId)==='error')throw new Error(data.taskSaveError(ref.taskId)||'修改尚未保存，已保留草稿')
  if(member===session.userId)mode.value=''
 }catch(e){if(member===session.userId){error.value=e instanceof Error?e.message:'操作失败，请重试';if(!mode.value)mode.value='error'}}
 finally{saving.value=false}
}
function setDate(value:string) {
 const ref={...source.value}
 return run(()=>{if(ref.subId)data.updateSub(ref.taskId,ref.subId,{due:value});else data.patchTask(ref.taskId,{due:value})},true)
}
async function apply(){
 if(mode.value==='delete'){await remove();return}
 if(mode.value==='date'){await setDate(date.value);return}
 const task=target.value, ref={...source.value}, dest=destination.value
 if(!task)return
 if(mode.value==='move')await run(()=>{
  const current=data.taskById(ref.taskId)
  if(!current||!movable(current))throw new Error('请先解除父子任务关系；内嵌子项随主任务移动')
  if(dest&&!data.projects.some(p=>p.id===dest))throw new Error('目标清单已不存在')
  if(!dest&&current.owner!==session.userId)throw new Error('只能将自己负责的任务移入收集箱')
  data.patchTask(ref.taskId,{project:dest,groupId:''})
 })
 else if(mode.value==='parent')await run(async()=>{
  if(dest&&!parents.value.some(t=>t.id===dest))throw new Error('目标主任务已不可用')
  if(ref.subId){if(!dest)throw new Error('请选择主任务');await data.moveSubtask(ref.taskId,ref.subId,dest,'')}
  else await data.moveTask(ref.taskId,dest||null)
 })
}
async function remove(){
 if(saving.value||!target.value)return
 const ref={...source.value}, member=session.userId
 saving.value=true;error.value=''
 try{
  if(data.movingTask||data.movingSubtasks||data.taskSaveState(ref.taskId)==='saving')throw new Error('任务正在保存或移动，请稍后重试')
  if(ref.subId){
   if(data.subById(ref.taskId,ref.subId))data.removeSub(ref.taskId,ref.subId)
   await data.flushTask(ref.taskId)
   if(data.taskSaveState(ref.taskId)==='error')throw new Error(data.taskSaveError(ref.taskId)||'删除尚未保存，请重试')
  }else if(!await data.deleteTask(ref.taskId))throw new Error('删除失败，任务已保留，请重试')
  if(member===session.userId)mode.value=''
 }catch(e){if(member===session.userId)error.value=e instanceof Error?e.message:'删除失败，请重试'}
 finally{saving.value=false}
}
function openAt(event:MouseEvent|KeyboardEvent,task:Task,extra:ContextAction[]=[]) {
 target.value=task;error.value=''
 const ref=actions.source(task.id), disabled=actions.busy(task.id)||saving.value
 source.value={taskId:ref.taskId,subId:ref.subId}
 const today=todayIso()
 const items:ContextAction[]=[
  {id:'today',label:'今天',icon:'ri-sun-line',quickGroup:'日期',disabled,run:()=>setDate(today)},
  {id:'tomorrow',label:'明天',icon:'ri-sun-foggy-line',quickGroup:'日期',disabled,run:()=>setDate(shiftDay(today,1))},
  {id:'week',label:'一周后',icon:'ri-calendar-event-line',quickGroup:'日期',disabled,run:()=>setDate(shiftDay(today,7))},
  {id:'date',label:'选择日期',icon:'ri-calendar-2-line',quickGroup:'日期',disabled,run:()=>dialog('date')},
  {id:'clear-date',label:'清除日期',icon:'ri-calendar-close-line',quickGroup:'日期',disabled:disabled||!task.due,run:()=>setDate('')},
  ...(['P0','P1','P2','P3'] as Priority[]).map(pr=>({id:pr,label:`${pr} · ${PROJECT_PRIORITY_LABELS[pr]}`,icon:pr==='P3'?'ri-flag-line':'ri-flag-fill',color:PROJECT_PRIORITY_COLORS[pr],quickGroup:'优先级',checked:task.pr===pr,disabled,run:()=>run(()=>actions.priority(task.id,pr))})),
  ...(extra.filter(item=>item.id==='add-child')),
  ...(!extra.some(item=>item.id==='add-child')?[{id:'add-child',label:'添加子任务',icon:'ri-node-tree',disabled,run:()=>{quick.value={...ref,mode:'child' as const}}}]:[]),
  {id:'parent',label:task.parentTaskId?'调整主任务':'关联主任务',icon:'ri-links-line',disabled,run:()=>dialog('parent')},
  {id:'move',label:'移动到',icon:'ri-folder-transfer-line',disabled:disabled||!movable(task),run:()=>dialog('move')},
  {id:'tags',label:'标签',icon:'ri-price-tag-3-line',run:()=>{quick.value={...ref,mode:'tags'}}},
  {id:'focus',label:'开始专注',icon:'ri-timer-line',separatorBefore:true,run:async()=>{await router.push({path:'/focus',query:{focusTask:ref.taskId,focusSub:ref.subId}})}},
  {id:'link',label:'复制链接',icon:'ri-link',separatorBefore:true,run:()=>copyText(shareUrl(ref.taskId,ref.subId),'任务链接已复制')},
  {id:'id',label:'复制任务编号',icon:'ri-hashtag',run:()=>copyText(String(task.number??ref.subId??ref.taskId),'任务编号已复制')},
  {id:'open',label:'打开任务详情',icon:'ri-file-list-3-line',run:()=>actions.open(task.id)},
  ...extra.filter(item=>item.id!=='add-child'),
  ...(!extra.some(item=>item.id==='delete')?[{id:'delete',label:ref.subId?'删除子任务':'删除任务',icon:'ri-delete-bin-line',danger:true,separatorBefore:true,disabled,run:()=>dialog('delete')}]:[]),
 ]
 return menu.value?.openAt(event,`任务：${task.title||'未命名任务'}`,items,event.type==='click'?event.currentTarget as HTMLElement:undefined)
}
defineExpose({openAt,close})
</script>
<template>
 <ContextActionMenu ref="menu" />
 <TaskQuickActionDialog v-if="quick" v-bind="quick" @close="quick=undefined" />
 <SettingsDialogShell v-if="mode" :title="({date:'选择日期',move:'移动到',parent:'关联主任务',delete:'删除任务',error:'操作尚未完成'})[mode]" width="440px" height="320px" :close-disabled="saving" @close="mode=''">
  <form :id="formId" class="task-menu-form" @submit.prevent="apply">
   <label v-if="mode==='date'">截止日期<input v-model="date" type="date" :disabled="saving" /></label>
   <label v-if="mode==='move'">目标清单<select v-model="destination" :disabled="saving"><option value="">收集箱</option><option v-for="p in data.projects" :key="p.id" :value="p.id">{{p.name}}</option></select><small>内嵌子项随主任务移动。</small></label>
   <label v-if="mode==='parent'">主任务<select v-model="destination" :disabled="saving"><option v-if="!source.subId" value="">无主任务（移至顶层）</option><option v-else value="" disabled>请选择主任务</option><option v-for="t in parents" :key="t.id" :value="t.id">{{t.title}}</option></select></label>
   <div v-if="mode==='delete'">确定删除「{{target?.title}}」？<p>{{source.subId?'下级子任务会保留并上移一级。':'内嵌子事项会一并删除，独立子任务会保留并移回顶层。'}}</p></div>
   <p v-if="error" role="alert">{{error}}</p>

  </form>
  <template #footer><div class="task-menu-footer"><WaoButton type="button" :disabled="saving" @click="mode=''">关闭</WaoButton><WaoButton v-if="error" type="button" @click="mode='';ui.openTask(source.taskId)">查看保存问题</WaoButton><WaoButton v-if="mode!=='error'" type="submit" :form="formId" :variant="mode==='delete'?'danger':'primary'" :loading="saving">{{mode==='delete'?(error?'重试删除':'确认删除'):'确定'}}</WaoButton></div></template>
 </SettingsDialogShell>
</template>
<style scoped>
.task-menu-form {padding:var(--wao-space-3);font:400 var(--wao-font-body)/1.7 var(--wao-font);}
label {display:flex;flex-direction:column;gap:var(--wao-space-1);}
input,select {width:100%;min-width:0;box-sizing:border-box;min-height:var(--wao-control-height);padding:var(--wao-space-1);font:inherit;color:var(--wao-text);border:1px solid var(--wao-line-control);border-radius:var(--wao-radius-control);background:var(--wao-bg-card);}
small {color:var(--wao-text-3);}p {color:var(--wao-danger-strong);overflow-wrap:anywhere;}.task-menu-footer {display:flex;flex-wrap:wrap;justify-content:flex-end;gap:var(--wao-space-1);}
</style>
