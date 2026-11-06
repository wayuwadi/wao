<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { createTaskFromTemplate, deleteTaskTemplate, fetchTaskTemplates, type TaskTemplate } from '@/api/taskTemplates'
import type { ConfirmAction } from '@/composables/confirmAction'
import { toast } from '@/composables/toast'
import ConfirmActionDialog from '@/components/ui/ConfirmActionDialog.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'

const props=defineProps<{projectId:string;destination:string;invalidDestination?:boolean}>()
const emit=defineEmits<{close:[]}>()
const data=useDataStore(), session=useSessionStore(), ui=useUiStore()
const accountId=session.account?.accountId ?? ''
const owner=`${accountId}:${session.userId}`
const key=`wao-task-template-create:${owner}`
const keyPrefix=`${key}:`
type Pending={id:string;projectId:string;templateId:string;title:string}
const pending=ref<Pending | null>(null), templates=ref<TaskTemplate[]>([])
const pendingKey=ref('')
const loading=ref(false), working=ref(false), error=ref(''), draftProblem=ref(false), confirmation=ref<ConfirmAction | null>(null)
const pendingDestination=computed(()=>pending.value?.projectId ? data.projectById(pending.value.projectId)?.name ?? '原清单（当前不可用）' : '收集箱')
const currentOwner=()=>`${session.account?.accountId ?? ''}:${session.userId}`
function loadPending() {
  try {
    const keys=Array.from({length:localStorage.length},(_,index)=>localStorage.key(index))
      .filter((item):item is string=>!!item && (item===key || item.startsWith(keyPrefix))).sort()
    const storedKey=keys[0]
    pendingKey.value=storedKey ?? ''
    if(!storedKey){pending.value=null;draftProblem.value=false;return}
    const raw=localStorage.getItem(storedKey)
    if(!raw)throw new Error('本机创建草稿无法读取，请先处理草稿。')
    const value=JSON.parse(raw)
    if(!/^[a-f0-9]{32}$/.test(value?.id) || !/^[a-f0-9]{32}$/.test(value?.templateId) || typeof value.projectId!=='string' || typeof value.title!=='string'
      || (storedKey!==key && storedKey!==`${keyPrefix}${value.id}`))throw new Error('本机创建草稿无法读取，请先处理草稿。')
    pending.value=value
    draftProblem.value=false
  } catch(e) { pending.value=null;draftProblem.value=true;error.value=e instanceof Error?e.message:'无法读取本机创建草稿。' }
}
loadPending()
watch([()=>session.userId,()=>session.account?.accountId],()=>emit('close'),{flush:'sync'})
async function load() {
  loading.value=true
  try { const result=await fetchTaskTemplates(); if(currentOwner()===owner){templates.value=result;if(!draftProblem.value)error.value=''} }
  catch { if(currentOwner()===owner)error.value='模板读取失败，请重试。' }
  finally { loading.value=false }
}
onMounted(load)
async function create(template?:TaskTemplate) {
  if(working.value || draftProblem.value)return
  if(!pending.value) {
    if(!template || props.invalidDestination){error.value='当前清单不可用，请重新选择清单。';return}
    pending.value={id:crypto.randomUUID().replace(/-/g,''),projectId:props.projectId,templateId:template.id,title:template.content.title}
    pendingKey.value=`${keyPrefix}${pending.value.id}`
  }
  const request=pending.value
  const requestKey=pendingKey.value
  try { localStorage.setItem(requestKey,JSON.stringify(request)) }
  catch { error.value='无法保存创建草稿，任务尚未创建，请检查本机存储后重试。';return }
  working.value=true;error.value=''
  try {
    const task=await createTaskFromTemplate(request.id,accountId,request.templateId,request.projectId)
    if(currentOwner()!==owner)return
    if(!task || task.id!==request.id)throw new Error('服务器返回的任务编号不匹配，请重试。')
    // A retry must not replace local edits made after the original creation.
    if(!data.taskById(task.id))data.tasks.unshift(task)
    try {
      const stored=localStorage.getItem(requestKey)
      if(stored && JSON.parse(stored).id===request.id)localStorage.removeItem(requestKey)
    }
    catch { error.value='任务已创建，但无法清理本机重试记录；可先查看任务，再放弃重试。';return }
    loadPending()
    toast('已从模板创建任务','ri-check-line')
    if(!pending.value && !draftProblem.value)emit('close')
  } catch(e) { if(currentOwner()===owner)error.value=e instanceof Error?e.message:'创建未完成，请重试。' }
  finally { working.value=false }
}
function discardPending() {
  const recordKey=pendingKey.value
  let original: string | null
  try { original=localStorage.getItem(recordKey) }
  catch { error.value='无法读取本机创建草稿，请检查本机存储。';return }
  confirmation.value={title:'放弃重试',description:'之前的创建请求可能已成功。放弃后，请先检查任务列表，避免再次创建重复任务。',confirmLabel:'放弃重试',run:()=>{
    if(localStorage.getItem(recordKey)!==original)throw new Error('创建草稿已变化，请重新检查。')
    localStorage.removeItem(recordKey);error.value='';loadPending()
  }}
}
function viewPending() {
  if(!pending.value || !data.taskById(pending.value.id))return
  ui.openTask(pending.value.id)
  emit('close')
}
function remove(template:TaskTemplate) {
  confirmation.value={title:'删除模板',description:`确定删除模板「${template.content.name}」？已创建的任务不受影响。`,run:async()=>{
    await deleteTaskTemplate(template.id,accountId)
    if(currentOwner()===owner)templates.value=templates.value.filter(item=>item.id!==template.id)
  }}
}
</script>
<template>
  <SettingsDialogShell title="使用任务模板" width="560px" height="auto" :close-disabled="working" @close="!working && emit('close')">
    <div class="template-picker">
      <p v-if="!pending">新任务将添加到「{{destination}}」，保留模板的标题、描述和子任务。</p>
      <div v-if="pending || draftProblem" class="pending" role="status"><span>{{pending ? `有一项创建尚未确认：${pending.title} · ${pendingDestination}` : '本机创建草稿无法读取'}}</span><WaoButton v-if="pending" variant="primary" :loading="working" @click="create()">重试创建</WaoButton><WaoButton variant="ghost" :disabled="working" @click="discardPending">放弃重试</WaoButton></div>
      <WaoButton v-if="pending && data.taskById(pending.id)" variant="ghost" :disabled="working" @click="viewPending">查看已创建的任务</WaoButton>
      <p v-if="loading" role="status">正在读取模板…</p>
      <p v-if="error" class="error" role="alert">{{error}} <button v-if="!pending && !draftProblem" type="button" @click="load">重试读取</button></p>
      <p v-if="!loading && !error && !templates.length">暂无模板，可在任务的更多菜单中保存。</p>
      <div v-for="template in templates" :key="template.id" class="template-row">
        <span><strong>{{template.content.name}}</strong><small>{{template.content.steps.length}} 个子任务</small></span>
        <WaoButton variant="ghost" :disabled="!!pending || draftProblem || working" :aria-label="`删除模板 ${template.content.name}`" @click="remove(template)">删除</WaoButton>
        <WaoButton variant="primary" :disabled="!!pending || draftProblem || working || invalidDestination" :aria-label="`使用模板 ${template.content.name}`" @click="create(template)">使用</WaoButton>
      </div>
    </div>
    <template #footer><WaoButton :disabled="working" @click="emit('close')">关闭</WaoButton></template>
  </SettingsDialogShell>
  <ConfirmActionDialog v-if="confirmation" :action="confirmation" @close="confirmation=null" />
</template>
<style scoped>
.template-picker { display:flex; flex-direction:column; gap:var(--wao-space-2); padding:var(--wao-space-3); max-height:55vh; overflow:auto; }
p { margin:0; color:var(--wao-text-3); font-size:var(--wao-font-help); }.error { color:var(--wao-danger-strong); }
.pending,.template-row { display:flex; align-items:center; flex-wrap:wrap; gap:var(--wao-space-1); padding-bottom:var(--wao-space-1); border-bottom:1px solid var(--wao-line); }
.pending span,.template-row span { flex:1; min-width:120px; overflow-wrap:anywhere; }.template-row small { display:block; color:var(--wao-text-3); }
.error button { border:0; background:transparent; color:var(--wao-primary-text); font:inherit; cursor:pointer; }
</style>
