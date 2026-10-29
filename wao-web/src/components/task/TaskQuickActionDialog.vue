<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import TaskLabelEditor from './TaskLabelEditor.vue'
import { priorityFeedback } from '@/composables/taskChangeFeedback'
import type { Priority } from '@/types'
const props=defineProps<{taskId:string;subId?:string;mode:'tags'|'child'|'priority'}>()
const emit=defineEmits<{close:[]}>()
const data=useDataStore(), session=useSessionStore()
const text=ref(''), pendingId=ref(''), saving=ref(false), error=ref('')
const priority=ref<Priority | ''>('P2')
const key=computed(()=>`wao-quick-child:${session.userId}:${props.taskId}:${props.subId??''}`)
watch(()=>[key.value,props.mode],()=>{
 error.value='';pendingId.value='';text.value=''
 priority.value=props.subId ? data.subById(props.taskId,props.subId)?.pr ?? '' : data.taskById(props.taskId)?.pr ?? 'P2'
 if(props.mode==='child')try { const draft=JSON.parse(localStorage.getItem(key.value)||'null');text.value=draft?.text||'';pendingId.value=draft?.id||'' }catch{error.value='草稿读取失败，请保持窗口打开'}
},{immediate:true})
function protect(){try{localStorage.setItem(key.value,JSON.stringify({text:text.value,id:pendingId.value}))}catch{error.value='本机草稿无法保存，请保持窗口打开'}}
async function save(){
 if(saving.value)return
 const previousPriority=props.subId?data.subById(props.taskId,props.subId)?.pr:data.taskById(props.taskId)?.pr
 saving.value=true;error.value=''
 try{
  if(!data.taskById(props.taskId)||(props.subId&&!data.subById(props.taskId,props.subId)))throw new Error('任务已不存在，请重新打开')
  if(props.mode==='child'){
   if(!text.value.trim())return
   if(!pendingId.value)pendingId.value=data.addSub(props.taskId,text.value.trim(),props.subId)||''
   if(!pendingId.value)throw new Error('未能添加子任务')
   protect()
  }else if(props.mode==='priority'){
   if(props.subId)data.updateSub(props.taskId,props.subId,{pr:priority.value || undefined})
   else if(priority.value) data.patchTask(props.taskId,{pr:priority.value})
  }
  await data.flushTask(props.taskId)
  if(data.taskSaveState(props.taskId)==='error')throw new Error('保存失败，修改已保留，请重试')
  if(props.mode==='child')localStorage.removeItem(key.value)
  if(props.mode==='priority')priorityFeedback(props.taskId,props.subId,previousPriority,priority.value||undefined)
  emit('close')
 }catch(e:any){error.value=e.message||'保存失败，请重试'}finally{saving.value=false}
}
</script>
<template>
 <SettingsDialogShell :title="mode==='tags'?'设置标签':mode==='child'?'添加子任务':'修改优先级'" width="420px" height="auto" @close="!saving&&emit('close')">
  <div class="quick-action">
   <TaskLabelEditor v-if="mode==='tags'" :task-id="taskId" :sub-id="subId" auto-open inline @done="emit('close')" />
   <form v-else @submit.prevent="save">
    <input v-if="mode==='child'" v-model="text" aria-label="子任务标题" placeholder="输入子任务标题" :disabled="saving||!!pendingId" autofocus @input="protect" />
    <label v-else>优先级<select v-model="priority" aria-label="优先级" :disabled="saving"><option v-if="subId" value="">跟随父任务</option><option value="P0">P0 · 最高</option><option value="P1">P1 · 高</option><option value="P2">P2 · 中</option><option value="P3">P3 · 低</option></select></label>
    <p v-if="error" role="alert">{{error}}</p>
    <footer><WaoButton type="button" :disabled="saving" @click="emit('close')">取消</WaoButton><WaoButton type="submit" :disabled="saving||(mode==='child'&&!text.trim())">{{saving?'保存中…':pendingId?'重试保存':'保存'}}</WaoButton></footer>
   </form>
  </div>
 </SettingsDialogShell>
</template>
<style scoped>
.quick-action {padding:var(--wao-space-3);font:400 var(--wao-font-body)/1.5 var(--wao-font);}
form,label {display:flex;flex-direction:column;gap:var(--wao-space-2);}
input,select {width:100%;box-sizing:border-box;padding:var(--wao-space-1);font:inherit;color:var(--wao-text);background:var(--wao-bg-card);border:1px solid var(--wao-line-control);border-radius:var(--wao-radius-control);}
footer {display:flex;justify-content:flex-end;gap:var(--wao-space-1);}
p {color:var(--wao-danger-strong);}
</style>
