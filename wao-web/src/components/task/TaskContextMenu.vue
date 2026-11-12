<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import TaskQuickActionDialog from './TaskQuickActionDialog.vue'
import TaskTemplateSaveDialog from './TaskTemplateSaveDialog.vue'
import { useTaskPinsStore } from '@/stores/taskPins'
import type { TaskMenuAction } from '@/api/taskMenu'
import { deadlineFeedback } from '@/composables/taskChangeFeedback'
import { toast } from '@/composables/toast'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useProjectItemActions } from '@/composables/projectItemActions'
import { PROJECT_PRIORITY_COLORS, PROJECT_PRIORITY_LABELS } from '@/utils/projectStatusPresentation'
import { shareUrl } from '@/utils/share'
import { copyText } from '@/utils/clipboard'
import { shiftDay } from '@/utils/taskDeadline'
import { absoluteTime, relativeTime, todayIso } from '@/utils/time'
import { activitySentence } from '@/utils/activity'
import { downloadTaskMarkdown, exportTaskSelection, printTaskMarkdown } from '@/utils/taskSelectionExport'
import type { Task, Priority } from '@/types'
import type { ContextAction } from '@/types/contextAction'
import type { ProjectItemRefs } from '@/utils/projectWorkItems'
const props=defineProps<{itemRefs?:ProjectItemRefs;inlineSelection?:boolean}>()
const emit=defineEmits<{ dateChanging:[id:string]; created:[id:string]; changed:[] }>()
const data=useDataStore(), session=useSessionStore(), ui=useUiStore()
const pins=useTaskPinsStore()
const actions=useProjectItemActions(()=>props.itemRefs)
const menu=ref<InstanceType<typeof ContextActionMenu>>()
const formId=useId()
const target=ref<Task>(), mode=ref<''|'date'|'move'|'parent'|'delete'|'abandon'|'note'|'activity'|'error'>('')
const date=ref(''), destination=ref(''), saving=ref(false), error=ref('')
const quick=ref<{taskId:string;subId?:string;mode:'tags'|'child'|'priority'}>()
const source=ref<{taskId:string;subId?:string}>({taskId:''})
const templateTarget=ref<{taskId:string;subId?:string} | null>(null)
const attachmentInput=ref<HTMLInputElement | null>(null)
const attachmentTarget=ref<{taskId:string;member:string;account:string | undefined;epoch:number} | null>(null)
const uploading=ref(false)
let identityEpoch=0, alive=true
onBeforeUnmount(()=>{alive=false;identityEpoch++})
const activities=computed(()=>(data.taskById(source.value.taskId)?.activity ?? []).map(item=>({
 ...item, description:activitySentence(item,session.userId,id=>session.memberById(id).name),
})))
const descendants = (id:string) => {
 const ids=new Set([id]); let changed=true
 while(changed){changed=false;for(const task of data.tasks)if(task.parentTaskId&&ids.has(task.parentTaskId)&&!ids.has(task.id)){ids.add(task.id);changed=true}}
 return ids
}
const parents=computed(()=>{
 const task=data.taskById(source.value.taskId), excluded=descendants(source.value.taskId)
 return data.tasks.filter(t=>t.workItemType!=='note' && t.status!=='已放弃' && t.project===task?.project && (source.value.subId || !excluded.has(t.id)))
})
function close(){menu.value?.close()}
watch([()=>session.userId,()=>session.account?.accountId],()=>{identityEpoch++;date.value='';destination.value='';close();mode.value='';quick.value=undefined;templateTarget.value=null;target.value=undefined;source.value={taskId:''};saving.value=false;uploading.value=false;attachmentTarget.value=null;error.value=''},{flush:'sync'})
function dialog(value:'date'|'move'|'parent'|'delete'|'abandon'|'note'|'activity') {
 if(!target.value)return
 mode.value=value;error.value='';date.value=target.value.due||todayIso()
 destination.value=value==='move'?target.value.project:value==='parent'?(target.value.parentTaskId??''):''
}
function chooseAttachment() {
 if(!target.value || source.value.subId || uploading.value)return
 attachmentTarget.value={taskId:source.value.taskId,member:session.userId,account:session.account?.accountId,epoch:identityEpoch}
 attachmentInput.value?.click()
}
async function uploadAttachment(event:Event) {
 const input=event.target as HTMLInputElement, file=input.files?.[0], selected=attachmentTarget.value
 input.value='';attachmentTarget.value=null
 if(!file || !selected || uploading.value || selected.member!==session.userId || selected.account!==session.account?.accountId || selected.epoch!==identityEpoch || !alive)return
 if(file.size>data.MAX_FILE_BYTES){toast('文件超过 200MB，无法上传','ri-error-warning-line');return}
 uploading.value=true
 try {
  const uploaded=await data.uploadFile(file)
  if(selected.member!==session.userId || selected.account!==session.account?.accountId || selected.epoch!==identityEpoch || !alive)return
  if(!uploaded?.id)throw new Error('文件上传失败，请重试')
  const current=data.taskById(selected.taskId)
  if(!current)throw new Error('文件已上传，但任务已不存在，可在文件中查看')
  if(!current.files.some(item=>item.id===uploaded.id)) data.patchTask(current.id,{files:[...current.files,{id:uploaded.id,name:uploaded.name,size:uploaded.size,icon:uploaded.icon}]},`上传并关联了文件「${uploaded.name}」`)
  if(!current.files.some(item=>item.id===uploaded.id))throw new Error('文件已上传，但任务关联未能保存，可在文件中查看')
  await data.flushTask(current.id)
  if(selected.member!==session.userId || selected.account!==session.account?.accountId || selected.epoch!==identityEpoch || !alive)return
  if(data.taskSaveState(current.id)==='error')throw new Error('文件已上传，关联保存在任务草稿中，请重试保存任务')
  toast('文件已上传并关联到任务','ri-check-line')
 }catch(e){if(selected.member===session.userId && selected.account===session.account?.accountId && selected.epoch===identityEpoch && alive)toast(e instanceof Error?e.message:'文件上传失败，请重试','ri-error-warning-line')}
 finally{if(selected.epoch===identityEpoch && alive)uploading.value=false}
}
function outputTask(kind:'download'|'print') {
 try {
  const snapshot=exportTaskSelection(data.tasks,source.value.taskId,source.value.subId,{
   origin:window.location.origin,
   projectName:id=>data.projectById(id)?.name ?? (id || '收集箱'),
   memberName:id=>session.memberById(id).name,
  })
  if(kind==='download')downloadTaskMarkdown(snapshot.title,snapshot.markdown)
  else printTaskMarkdown(snapshot.title,snapshot.markdown)
 }catch(e){toast(e instanceof Error?e.message:'无法生成任务快照，请重试','ri-error-warning-line')}
}
async function run(change:(current:()=>boolean)=>void|Promise<void>, dateChange=false):Promise<boolean> {
 const task=target.value, epoch=identityEpoch
 const current=()=>alive && epoch===identityEpoch
 if(!task||saving.value)return false
 const ref=actions.source(task.id)
 saving.value=true;error.value=''
 try {
  if(actions.busy(task.id))throw new Error('任务正在保存或移动，请稍后重试')
  if(!data.taskById(ref.taskId)||(ref.subId&&!data.subById(ref.taskId,ref.subId)))throw new Error('任务已不存在')
  if(data.taskSaveState(ref.taskId)==='error')throw new Error('请先在任务详情中处理已有的保存问题')
  if(dateChange)emit('dateChanging',task.id)
  await change(current)
  if(!current())return false
  await data.flushTask(ref.taskId)
  if(!current())return false
  if(data.taskSaveState(ref.taskId)==='error')throw new Error(data.taskSaveError(ref.taskId)||'修改尚未保存，已保留草稿')
  mode.value=''
  return true
 }catch(e){if(current()){error.value=e instanceof Error?e.message:'操作失败，请重试';if(!mode.value)mode.value='error'}return false}
 finally{if(current())saving.value=false}
}
async function setDate(value:string) {
 const ref={...source.value}, epoch=identityEpoch
 const before=ref.subId?data.subById(ref.taskId,ref.subId)?.due:data.taskById(ref.taskId)?.due
 const saved=await run(()=>{if(ref.subId)data.updateSub(ref.taskId,ref.subId,{due:value});else data.patchTask(ref.taskId,{due:value})},true)
 if(saved && alive && epoch===identityEpoch)deadlineFeedback([{...ref,before,after:value}])
}
async function apply(){
 if(mode.value==='abandon'||mode.value==='note'){await menuAction(mode.value);return}
 if(mode.value==='delete'){await remove();return}
 if(mode.value==='date'){await setDate(date.value);return}
 const task=target.value, ref={...source.value}, dest=destination.value
 if(!task)return
 if(mode.value==='move')await run(()=>{
  const current=data.taskById(ref.taskId)
  if(!current||!actions.canMoveToList(task.id))throw new Error('请先解除父子任务关系；内嵌子项随主任务移动')
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
 const ref={...source.value}, epoch=identityEpoch
 const current=()=>alive && epoch===identityEpoch
 saving.value=true;error.value=''
 try{
  if(data.movingTask||data.movingSubtasks||data.taskSaveState(ref.taskId)==='saving')throw new Error('任务正在保存或移动，请稍后重试')
  if(ref.subId){
   if(data.subById(ref.taskId,ref.subId))data.removeSub(ref.taskId,ref.subId)
   await data.flushTask(ref.taskId)
   if(!current())return
   if(data.taskSaveState(ref.taskId)==='error')throw new Error(data.taskSaveError(ref.taskId)||'删除尚未保存，请重试')
  }else if(!await data.deleteTask(ref.taskId))throw new Error('删除失败，任务已保留，请重试')
  if(current())mode.value=''
 }catch(e){if(current())error.value=e instanceof Error?e.message:'删除失败，请重试'}
 finally{if(current())saving.value=false}
}
async function menuAction(action:TaskMenuAction) {
 const ref={...source.value}
 await run(async(current)=>{
   const selected=await data.taskMenuOperation(ref.taskId,ref.subId,action)
   if(!current())return
   if(action!=='duplicate')emit('changed')
   toast(({abandon:'已移至已放弃',restore:'已重新开始',duplicate:'已创建副本',note:'已转换为笔记，保留在当前清单',task:'已转换为任务'})[action],'ri-check-line')
   if(action==='duplicate' && selected){emit('created',selected);if(props.inlineSelection)return;if(selected.startsWith('sub:')){const [,taskId,subId]=selected.split(':');ui.openTask(taskId!,subId)}else ui.openTask(selected)}
 })
}
function openAt(event:MouseEvent|KeyboardEvent,task:Task,extra:ContextAction[]=[]) {
 if(saving.value)return
 target.value=task;error.value=''
 const ref=actions.source(task.id), disabled=actions.busy(task.id)||saving.value
 source.value={taskId:ref.taskId,subId:ref.subId}
 const today=todayIso(), note=task.workItemType==='note', abandoned=task.status==='已放弃'
 const hasChildren=task.subs.length>0||data.tasks.some(t=>t.parentTaskId===task.id)
 const items:ContextAction[]=[
  {id:'today',label:'今天',icon:'ri-sun-line',quickGroup:'日期',disabled,run:()=>setDate(today)},
  {id:'tomorrow',label:'明天',icon:'ri-sun-foggy-line',quickGroup:'日期',disabled,run:()=>setDate(shiftDay(today,1))},
  {id:'week',label:'一周后',icon:'ri-calendar-event-line',quickGroup:'日期',disabled,run:()=>setDate(shiftDay(today,7))},
  {id:'date',label:'选择日期',icon:'ri-calendar-2-line',quickGroup:'日期',disabled,run:()=>dialog('date')},
  {id:'clear-date',label:'清除日期',icon:'ri-calendar-close-line',quickGroup:'日期',disabled:disabled||!task.due,run:()=>setDate('')},
  ...(['P0','P1','P2','P3'] as Priority[]).map(pr=>({id:pr,label:`${pr} · ${PROJECT_PRIORITY_LABELS[pr]}`,icon:pr==='P3'?'ri-flag-line':'ri-flag-fill',color:PROJECT_PRIORITY_COLORS[pr],quickGroup:'优先级',checked:task.pr===pr,disabled,run:async()=>{await run(()=>actions.priority(task.id,pr))}})),
  ...(extra.filter(item=>item.id==='add-child')),
  ...(!extra.some(item=>item.id==='add-child')?[{id:'add-child',label:'添加子任务',icon:'ri-node-tree',disabled,run:()=>{quick.value={...ref,mode:'child' as const}}}]:[]),
  {id:'pin',label:pins.has(task.id)?'取消置顶':'置顶',icon:'ri-pushpin-line',disabled,run:async()=>{await run(()=>pins.toggle(task.id))}},
  ...(pins.error?[{id:'retry-pin',label:'重试同步置顶',icon:'ri-refresh-line',run:async()=>{await run(async()=>{await pins.retry();if(pins.error)throw new Error(pins.error)})}}]:[]),
  {id:'abandon',label:abandoned?'重新开始':'放弃',icon:abandoned?'ri-restart-line':'ri-close-circle-line',disabled:disabled||note||task.status==='已完成',run:()=>abandoned?menuAction('restore'):dialog('abandon')},
  {id:'parent',label:task.parentTaskId?'调整主任务':'关联主任务',icon:'ri-links-line',disabled,run:()=>dialog('parent')},
  {id:'move',label:'移动到',icon:'ri-folder-transfer-line',disabled:disabled||!actions.canMoveToList(task.id),run:()=>dialog('move')},
  {id:'tags',label:'标签',icon:'ri-price-tag-3-line',run:()=>{quick.value={...ref,mode:'tags'}}},
  ...(!ref.subId?[{id:'attachment',label:'上传附件',icon:'ri-attachment-line',disabled:disabled||uploading.value,run:chooseAttachment}]:[]),
  {id:'activity',label:'任务动态',icon:'ri-list-check-2',run:()=>dialog('activity')},
  {id:'template',label:'保存为模板',icon:'ri-save-line',run:()=>{templateTarget.value={...ref}}},
  {id:'duplicate',label:'创建副本',icon:'ri-file-copy-line',separatorBefore:true,disabled,run:()=>menuAction('duplicate')},
  {id:'convert',label:note?'转换为任务':'转换为笔记',icon:note?'ri-checkbox-line':'ri-sticky-note-line',disabled:disabled||hasChildren||abandoned,run:()=>note?menuAction('task'):dialog('note')},
  {id:'link',label:'复制链接',icon:'ri-link',separatorBefore:true,run:()=>copyText(shareUrl(ref.taskId,ref.subId),'任务链接已复制')},
  {id:'export',label:'导出 Markdown',icon:'ri-upload-line',run:()=>outputTask('download')},
  {id:'print',label:'打印',icon:'ri-printer-line',run:()=>outputTask('print')},
  {id:'id',label:'复制任务编号',icon:'ri-hashtag',run:()=>copyText(String(task.number??ref.subId??ref.taskId),'任务编号已复制')},
  {id:'open',label:'打开任务详情',icon:'ri-file-list-3-line',run:()=>actions.open(task.id)},
  ...extra.filter(item=>item.id!=='add-child'),
  ...(!extra.some(item=>item.id==='delete')?[{id:'delete',label:ref.subId?'删除子任务':'删除任务',icon:'ri-delete-bin-line',danger:true,separatorBefore:true,disabled,run:()=>dialog('delete')}]:[]),
 ]
 for(const item of items) if(note && ['today','tomorrow','week','date','clear-date','P0','P1','P2','P3','add-child','focus'].includes(item.id))item.disabled=true
 return menu.value?.openAt(event,`任务：${task.title||'未命名任务'}`,items,event.type==='click'?event.currentTarget as HTMLElement:undefined)
}
function edit(task:Task,field:'date'|'priority'|'tags'|'move') {
 if(saving.value||actions.busy(task.id))return
 target.value=task;source.value=actions.source(task.id)
 if(field==='priority'||field==='tags')quick.value={...source.value,mode:field}
 else dialog(field)
}
defineExpose({openAt,close,edit})
</script>
<template>
 <ContextActionMenu ref="menu" />
 <input ref="attachmentInput" type="file" hidden @change="uploadAttachment" />
 <TaskQuickActionDialog v-if="quick" v-bind="quick" @close="quick=undefined" />
 <TaskTemplateSaveDialog v-if="templateTarget" v-bind="templateTarget" @close="templateTarget=null" />
 <SettingsDialogShell v-if="mode" :title="({date:'选择日期',move:'移动到',parent:'关联主任务',delete:'删除任务',abandon:'放弃任务',note:'转换为笔记',activity:'任务动态',error:'操作尚未完成'})[mode]" width="440px" height="320px" :close-disabled="saving" @close="mode=''">
  <form :id="formId" class="task-menu-form" @submit.prevent="apply">
   <label v-if="mode==='date'">截止日期<input v-model="date" type="date" :disabled="saving" /></label>
   <label v-if="mode==='move'">目标清单<select v-model="destination" :disabled="saving"><option value="">收集箱</option><option v-for="p in data.projects" :key="p.id" :value="p.id">{{p.name}}</option></select><small>内嵌子项随主任务移动。</small></label>
   <label v-if="mode==='parent'">主任务<select v-model="destination" :disabled="saving"><option v-if="!source.subId" value="">无主任务（移至顶层）</option><option v-else value="" disabled>请选择主任务</option><option v-for="t in parents" :key="t.id" :value="t.id">{{t.title}}</option></select></label>
   <div v-if="mode==='delete'">确定删除「{{target?.title}}」？<p>{{source.subId?'下级子任务会保留并上移一级。':'内嵌子事项会一并删除，独立子任务会保留并移回顶层。'}}</p></div>
   <p v-if="mode==='abandon'">放弃「{{target?.title}}」及其未完成子任务？之后可在“已放弃”中重新开始。</p>
   <p v-if="mode==='note'">转换后保留在当前清单，不再作为待办参与日期视图和四象限。内容与附件保留，也可以转换回任务。</p>
   <div v-if="mode==='activity'" class="activity-list"><small v-if="source.subId">显示所属主任务及其子任务的动态。</small><p v-if="!activities.length">暂无动态</p><div v-for="(item,index) in activities" :key="index" class="activity-item"><span>{{item.description}}</span><time :title="absoluteTime(item.time)">{{relativeTime(item.time)}}</time></div></div>
   <p v-if="error" role="alert">{{error}}</p>

  </form>
  <template #footer><div class="task-menu-footer"><WaoButton type="button" :disabled="saving" @click="mode=''">关闭</WaoButton><WaoButton v-if="error" type="button" @click="mode='';ui.openTask(source.taskId)">查看保存问题</WaoButton><WaoButton v-if="mode!=='error' && mode!=='activity'" type="submit" :form="formId" :variant="mode==='delete'?'danger':'primary'" :loading="saving">{{mode==='delete'?(error?'重试删除':'确认删除'):'确定'}}</WaoButton></div></template>
 </SettingsDialogShell>
</template>
<style scoped>
.task-menu-form {padding:var(--wao-space-3);font:400 var(--wao-font-body)/1.7 var(--wao-font);}
label {display:flex;flex-direction:column;gap:var(--wao-space-1);}
input,select {width:100%;min-width:0;box-sizing:border-box;min-height:var(--wao-control-height);padding:var(--wao-space-1);font:inherit;color:var(--wao-text);border:1px solid var(--wao-line-control);border-radius:var(--wao-radius-control);background:var(--wao-bg-card);}
small {color:var(--wao-text-3);}p {color:var(--wao-danger-strong);overflow-wrap:anywhere;}.task-menu-footer {display:flex;flex-wrap:wrap;justify-content:flex-end;gap:var(--wao-space-1);}
.activity-list { max-height:180px; overflow:auto; }.activity-list p { color:var(--wao-text-3); }.activity-item { display:flex; flex-direction:column; padding:var(--wao-space-1) 0; border-bottom:1px solid var(--wao-line); }.activity-item time { color:var(--wao-text-3); font-size:var(--wao-font-help); }
</style>
