<script setup lang="ts">
import { ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { saveTaskTemplate, type TaskTemplateContent } from '@/api/taskTemplates'
import { taskTemplateSnapshot } from '@/utils/taskTemplate'
import { toast } from '@/composables/toast'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'

const props=defineProps<{taskId:string;subId?:string}>()
const emit=defineEmits<{close:[]}>()
const data=useDataStore(), session=useSessionStore()
const accountId=session.account?.accountId ?? ''
const owner=`${accountId}:${session.userId}`
const key=`wao-task-template-save:${owner}:${props.taskId}:${props.subId ?? ''}`
const keyPrefix=`${key}:`
const name=ref(''), error=ref(''), saving=ref(false), draftProblem=ref(false), draftProtected=ref(true)
const pending=ref<{id:string;content:TaskTemplateContent} | null>(null)
const pendingKey=ref('')
let source:TaskTemplateContent | null=null
function loadPending() {
  try {
    const keys=Array.from({length:localStorage.length},(_,index)=>localStorage.key(index))
      .filter((item):item is string=>!!item && (item===key || item.startsWith(keyPrefix))).sort()
    const storedKey=keys[0]
    pendingKey.value=storedKey ?? ''
    if(!storedKey){pending.value=null;draftProblem.value=false;return}
    const saved=localStorage.getItem(storedKey)
    if(!saved)throw new Error('本机模板草稿无法读取，请先清除草稿。')
    const value=JSON.parse(saved)
    if(!/^[a-f0-9]{32}$/.test(value?.id) || !value.content || typeof value.content.name!=='string' || !Array.isArray(value.content.steps)
      || (storedKey!==key && storedKey!==`${keyPrefix}${value.id}`)) throw new Error('本机模板草稿无法读取，请先清除草稿。')
    pending.value=value
    name.value=value.content.name
    draftProblem.value=false
  } catch(e) { pending.value=null;draftProblem.value=true;error.value=e instanceof Error?e.message:'本机模板草稿无法读取，请保持窗口打开。' }
}
loadPending()
if(!pending.value && !draftProblem.value) {
  try {
    source=taskTemplateSnapshot(data.tasks,props.taskId,props.subId)
    name.value=source.name.slice(0,128)
  } catch(e) { draftProblem.value=true;error.value=e instanceof Error?e.message:'任务内容无法读取。' }
}
watch([()=>session.userId,()=>session.account?.accountId],()=>emit('close'),{flush:'sync'})
function discard() {
  try {
    if(draftProtected.value && pendingKey.value) {
      const raw=localStorage.getItem(pendingKey.value)
      if(pending.value && raw && JSON.parse(raw).id!==pending.value.id)throw new Error('模板草稿已变化，请重新打开。')
      localStorage.removeItem(pendingKey.value)
    }
    pending.value=null;error.value='';draftProtected.value=true;loadPending()
    if(!pending.value && !draftProblem.value){source=taskTemplateSnapshot(data.tasks,props.taskId,props.subId);name.value=source.name.slice(0,128)}
  }
  catch { error.value='无法清除本机草稿，请保持窗口打开。' }
}
async function save() {
  if(saving.value || !name.value.trim())return
  error.value=''
  if(!pending.value) {
    if(!source){error.value='任务已不可用，请重新打开。';return}
    pending.value={id:crypto.randomUUID().replace(/-/g,''),content:{...source,name:name.value.trim()}}
    pendingKey.value=`${keyPrefix}${pending.value.id}`
  }
  const snapshot=pending.value
  const requestKey=pendingKey.value
  try { localStorage.setItem(requestKey,JSON.stringify(snapshot));draftProtected.value=true }
  catch { draftProtected.value=false;error.value='本机草稿无法保存，模板尚未提交；请保持窗口打开并重试。';return }
  saving.value=true
  try {
    await saveTaskTemplate(snapshot.id,snapshot.content,accountId)
    if(owner!==`${session.account?.accountId ?? ''}:${session.userId}`)return
    try {
      const stored=localStorage.getItem(requestKey)
      if(stored && JSON.parse(stored).id===snapshot.id)localStorage.removeItem(requestKey)
    } catch { /* same operation ID remains safe to retry */ }
    toast('任务模板已保存','ri-check-line')
    loadPending()
    if(!pending.value && !draftProblem.value)emit('close')
  } catch(e) { if(owner===`${session.account?.accountId ?? ''}:${session.userId}`)error.value=e instanceof Error && e.message.includes('模板已删除') ? e.message : `${e instanceof Error && e.message ? `${e.message}。` : ''}模板尚未确认保存，请重试。` }
  finally { saving.value=false }
}
</script>

<template>
  <SettingsDialogShell title="保存为模板" width="440px" height="auto" :close-disabled="saving || !!pending && !draftProtected" @close="!saving && (!pending || draftProtected) && emit('close')">
    <form class="template-form" @submit.prevent="save">
      <label>模板名称<input v-model="name" maxlength="128" :disabled="saving || !!pending" aria-label="模板名称" /></label>
      <p>保留标题、描述和子任务；新任务负责人设为自己，日期、完成状态与评论重置。</p>
      <p v-if="pending || draftProblem">上次请求可能已经成功；放弃草稿前请先检查模板列表，避免重复保存。</p>
      <p v-if="error" role="alert" class="error">{{error}}</p>
      <div class="template-actions"><WaoButton v-if="(pending || draftProblem) && !saving" type="button" variant="ghost" @click="discard">放弃本次草稿</WaoButton><WaoButton type="button" :disabled="saving || !!pending && !draftProtected" @click="emit('close')">关闭</WaoButton><WaoButton type="submit" variant="primary" :loading="saving" :disabled="draftProblem || !name.trim() || !source && !pending">{{pending?'重试保存':'保存模板'}}</WaoButton></div>
    </form>
  </SettingsDialogShell>
</template>
<style scoped>
.template-form { display:flex; flex-direction:column; gap:var(--wao-space-2); padding:var(--wao-space-3); }
label { display:flex; flex-direction:column; gap:var(--wao-space-1); }
input { min-width:0; min-height:var(--wao-control-height); box-sizing:border-box; padding:var(--wao-space-1); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); background:var(--wao-bg-card); color:var(--wao-text); font:inherit; }
p { margin:0; color:var(--wao-text-3); font-size:var(--wao-font-help); }.error { color:var(--wao-danger-strong); }
.template-actions { display:flex; justify-content:flex-end; flex-wrap:wrap; gap:var(--wao-space-1); }
</style>
