<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { fetchTaskTime, saveTaskTime, skipTaskOccurrence, retryTaskTime, testTaskReminder, emptyTaskTimeRule, type TaskTimeRule, type TaskTimeState } from '@/api/taskTime'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoField from '@/components/ui/WaoField.vue'
const clone=<T,>(value:T):T=>JSON.parse(JSON.stringify(value))
const props = defineProps<{ taskId: string }>()
const session=useSessionStore(), data=useDataStore(), ui=useUiStore()
const open=ref(false), loading=ref(false), busy=ref(false), error=ref(''), draftError=ref(''), message=ref('')
const conflictFresh=ref(true)
const saved=ref<TaskTimeState>(), draft=ref<TaskTimeRule>(emptyTaskTimeRule()), conflict=ref(false), confirmSkip=ref(false)
const testOperation=ref('')
let epoch=0, reading=false
const identity=computed(()=>`${session.account?.accountId??''}:${session.userId}:${props.taskId}`)
const draftKey=computed(()=>`wao-task-time-draft:${identity.value}`)
const task=computed(()=>data.taskById(props.taskId))
const changed=computed(()=>!!saved.value && JSON.stringify(draft.value)!==JSON.stringify(saved.value.rule))
const units: Record<TaskTimeRule['repeat'],string>={none:'不重复',daily:'天',weekly:'周',monthly:'月',yearly:'年',weekdays:'工作日（周一至周五）'}
const at=(value:string|null|undefined)=>value?.replace('T',' ').slice(0,16)||'未设置'
function protect(){
  if(reading || !open.value || !saved.value)return
  try{localStorage.setItem(draftKey.value,JSON.stringify(draft.value));draftError.value=''}catch{draftError.value='本机草稿保存失败，请保持窗口打开并重试。'}
}
watch(draft,protect,{deep:true,flush:'sync'})
watch(identity,()=>{epoch++;reading=true;open.value=false;saved.value=undefined;draft.value=emptyTaskTimeRule();error.value='';draftError.value='';busy.value=false;loading.value=false;conflict.value=false;confirmSkip.value=false;testOperation.value='';reading=false},{flush:'sync'})
onBeforeUnmount(()=>{protect();epoch++})
function failure(e:unknown){return (e as {response?:{data?:{message?:string}}})?.response?.data?.message || (e instanceof Error?e.message:'操作未完成，请重试')}
async function load(){
  const generation=++epoch, id=props.taskId, key=draftKey.value
  loading.value=true;error.value='';message.value=''
  try{
    const result=await fetchTaskTime(id)
    if(generation!==epoch)return
    saved.value=result;conflictFresh.value=true;reading=true
    const local=localStorage.getItem(key)
    if(local){
      try { const candidate=JSON.parse(local) as TaskTimeRule; if(typeof candidate.revision!=='number'||!Array.isArray(candidate.remindBefore))throw new Error();draft.value={...emptyTaskTimeRule(),...candidate};conflict.value=JSON.stringify(candidate)!==JSON.stringify(result.rule)&&candidate.revision!==result.rule.revision }
      catch {draftError.value='旧草稿无法读取，原记录仍保留。请明确采用账号设置后继续。';draft.value=clone(result.rule);conflict.value=true}
    } else {draft.value=clone(result.rule);conflict.value=false}
  }catch(e){if(generation===epoch)error.value=failure(e)}finally{if(generation===epoch){reading=false;loading.value=false}}
}
function show(){open.value=true;confirmSkip.value=false;void load()}
function close(){if(busy.value)return;protect();if(!draftError.value)open.value=false}
function useSaved(){if(!saved.value || !conflictFresh.value)return;reading=true;draft.value=clone(saved.value.rule);reading=false;conflict.value=false;draftError.value='';protect()}
function keepDraft(){if(!saved.value || !conflictFresh.value)return;draft.value={...draft.value,revision:saved.value.rule.revision};conflict.value=false;protect()}
async function run(action:'save'|'skip'|'retry'|'test'){
  if(busy.value||!saved.value||loading.value||conflict.value)return
  const generation=epoch,id=props.taskId,key=draftKey.value
  busy.value=true;error.value='';message.value=''
  try{
    if(action==='save'||action==='skip'){
      await data.flushTask(id)
      if(generation!==epoch)return
      if(data.taskSaveState(id)==='error')throw new Error('任务日期尚未保存，请先处理任务中的保存问题')
    }
    let result:TaskTimeState
    if(action==='save')result=await saveTaskTime(id,clone(draft.value))
    else if(action==='skip'){
      if(!task.value?.editVersion)throw new Error('请先等待任务读取完成')
      result=await skipTaskOccurrence(id,saved.value.rule.revision,task.value.editVersion)
    }else if(action==='test'){testOperation.value ||= crypto.randomUUID();result=await testTaskReminder(id,testOperation.value)}
    else result=await retryTaskTime(id)
    if(generation!==epoch)return
    saved.value=result;reading=true;draft.value=clone(result.rule);reading=false
    try{localStorage.removeItem(key);draftError.value=''}catch{draftError.value='账号已保存，本机草稿未能清除，请保持窗口并重试。'}
    message.value=action==='save'?'时间规则已保存':action==='skip'?'已跳过本次，历史记录保留':action==='test'?'测试提醒已送达站内通知中心':'已重新检查提醒与重复规则'
    confirmSkip.value=false
    if(action==='test')testOperation.value=''
    if(action==='skip'||action==='retry')await data.hydrate(['tasks'])
  }catch(e){if(generation===epoch){error.value=failure(e);if((e as {response?:{status?:number}})?.response?.status===409){conflict.value=true;conflictFresh.value=false;try{const latest=await fetchTaskTime(id);if(generation===epoch){saved.value=latest;conflictFresh.value=true}}catch{if(generation===epoch)error.value+='；账号设置读取失败，请重新读取后比较'}}}}
  finally{if(generation===epoch){busy.value=false;reading=false}}
}
async function nextTask(){
  const id=saved.value?.nextTaskId,generation=epoch;if(!id||busy.value)return
  busy.value=true;error.value=''
  try{await data.hydrate(['tasks']);if(generation!==epoch)return;open.value=false;ui.openTask(id)}
  catch(e){if(generation===epoch)error.value=`下一次任务读取失败：${failure(e)}，请重试打开。`}
  finally{if(generation===epoch)busy.value=false}
}
</script>
<template>
  <WaoButton variant="ghost" type="button" aria-label="任务重复与提醒" @click="show"><i class="ri-repeat-line" aria-hidden="true" />重复与提醒</WaoButton>
  <SettingsDialogShell v-if="open" title="重复与提醒" icon="ri-calendar-check-line" width="600px" height="min(680px, calc(100dvh - 48px))" :close-disabled="busy || !!draftError" @close="close">
    <div class="task-time-settings">
      <p class="task-title">{{ task?.title }}</p>
      <p class="dates">执行：{{ at(task?.startAt || task?.start) }} · 截止：{{ at(task?.due) }}</p>
      <p v-if="loading" role="status">正在读取时间规则…</p>
      <p v-if="error" class="error" role="alert">{{ error }} <WaoButton type="button" :disabled="busy" @click="load">重新读取</WaoButton></p>
      <p v-if="draftError" class="error" role="alert">{{ draftError }} <WaoButton type="button" @click="protect">重试保护草稿</WaoButton></p>
      <template v-if="saved && !loading">
        <p v-if="!saved.schedulerEnabled" class="error" role="status">此部署尚未启用后台提醒，请联系维护者开启。重复任务仍会在完成后生成。</p>
        <p v-if="saved.reason" role="status">{{ saved.reason }} <WaoButton v-if="saved.nextTaskId" @click="nextTask">打开下一次任务</WaoButton></p>
        <div v-if="conflict" class="conflict" role="alert"><p>账号设置已有变化，当前草稿仍保留。账号规则：{{ saved.rule.repeat==='none'?'不重复':`每 ${saved.rule.interval} ${units[saved.rule.repeat]}` }}；{{ saved.rule.remindBefore.length }} 个提醒。</p><WaoButton :disabled="busy || !conflictFresh" @click="useSaved">采用账号设置</WaoButton><WaoButton :disabled="busy || !conflictFresh" @click="keepDraft">保留草稿，以当前版本继续编辑</WaoButton></div>
        <fieldset :disabled="busy || !saved.editable || conflict">
          <WaoField label="日期基准" v-slot="field"><select :id="field.id" v-model="draft.anchor"><option value="start">执行日期 / 开始时间</option><option value="due">截止日期</option></select></WaoField>
          <WaoField label="重复" v-slot="field"><select :id="field.id" v-model="draft.repeat"><option value="none">不重复</option><option value="daily">每天</option><option value="weekly">每周</option><option value="monthly">每月</option><option value="yearly">每年</option><option value="weekdays">工作日（周一至周五）</option></select></WaoField>
          <template v-if="draft.repeat!=='none'">
            <div class="two-fields"><WaoField label="每隔" v-slot="field"><div class="interval"><input :id="field.id" v-model.number="draft.interval" type="number" min="1" max="365" /><span>{{ units[draft.repeat] }}</span></div></WaoField><WaoField label="结束日期（可选）" v-slot="field"><input :id="field.id" v-model="draft.until" type="date" /></WaoField></div>
            <WaoField label="计算下一次" v-slot="field"><select :id="field.id" v-model="draft.mode"><option value="scheduled">按本次计划日期重复</option><option value="completion">按本次实际完成日期重复</option></select></WaoField>
            <p class="hint">主任务和未放弃的子任务全部完成后创建下一次，保留本次历史。计划重复跳过已过去的周期；工作日指周一至周五，不包含法定调休判断。内嵌子任务随整条任务重复。</p>
          </template>
          <WaoField label="站内提醒"><div class="reminder-options"><label v-for="option in [{value:0,label:'准时'},{value:10,label:'提前10分钟'},{value:30,label:'提前30分钟'},{value:60,label:'提前1小时'},{value:1440,label:'提前1天'}]" :key="option.value"><input v-model="draft.remindBefore" :disabled="!saved.schedulerEnabled" type="checkbox" :value="option.value" />{{ option.label }}</label></div></WaoField>
          <WaoField v-if="draft.remindBefore.length && (draft.anchor==='due' || !task?.startAt)" label="当天提醒时间（上海时间）" v-slot="field"><input :id="field.id" v-model="draft.reminderTime" type="time" /></WaoField>
          <p class="hint">提醒保存在 WAO 通知中心，仅你本人可见。关闭页面后仍会记录，重新打开可查看；这不是系统弹窗或飞书消息。取消全部勾选即关闭提醒。</p>
        </fieldset>
        <div class="status" role="status"><span v-if="saved.nextDate">按当前规则，下一次：{{ saved.nextDate }}</span><span v-if="saved.nextReminder">待提醒：{{ at(saved.nextReminder) }}</span><span v-if="saved.lastDeliveredAt">最近送达：{{ at(saved.lastDeliveredAt) }}</span><span v-if="saved.error" class="error">{{ saved.error }}</span><span v-if="message">{{ message }}</span></div>
        <div v-if="!changed && !conflict" class="secondary-actions"><WaoButton v-if="saved.editable && saved.rule.remindBefore.length" :disabled="busy" @click="run('test')">试发站内提醒</WaoButton><WaoButton v-if="saved.error" :disabled="busy" @click="run('retry')">重试处理</WaoButton><WaoButton v-if="saved.editable && saved.rule.repeat!=='none'" :disabled="busy" @click="confirmSkip=true">跳过本次</WaoButton></div>
        <div v-if="confirmSkip" class="skip-confirm"><p>本次将移到已放弃，按当前规则生成下一次，原记录和内容仍保留。</p><WaoButton :disabled="busy" @click="confirmSkip=false">取消</WaoButton><WaoButton :disabled="busy" @click="run('skip')">确认跳过本次</WaoButton></div>
      </template>
    </div>
    <template #footer><span class="footer-state">{{ busy?'正在保存…':changed?'有未保存修改':'上海时间' }}</span><WaoButton :disabled="busy || !!draftError" @click="close">关闭</WaoButton><WaoButton variant="primary" :disabled="busy || loading || !saved?.editable || conflict || !!draftError" @click="run('save')">保存时间规则</WaoButton></template>
  </SettingsDialogShell>
</template>
<style scoped>
.task-time-settings { padding:var(--wao-space-2); color:var(--wao-text); font:400 var(--wao-font-body)/1.6 var(--wao-font); }
p { margin:0 0 var(--wao-space-2); } .task-title { font-weight:var(--wao-weight-heading); overflow-wrap:anywhere; } .dates,.hint,.status,.footer-state { color:var(--wao-text-3); font-size:var(--wao-font-help); }
fieldset { border:0; padding:0; margin:0; display:grid; gap:var(--wao-space-2); min-width:0; } .two-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:var(--wao-space-2); }
input:not([type=checkbox]),select { box-sizing:border-box; width:100%; min-width:0; min-height:38px; padding:var(--wao-space-1); color:var(--wao-text); background:var(--wao-bg-card); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); font:inherit; }
.interval,.reminder-options,.secondary-actions { display:flex; gap:var(--wao-space-1); align-items:center; flex-wrap:wrap; } .interval input { width:80px; } .reminder-options label { display:flex; gap:4px; align-items:center; min-height:32px; }
.status { display:grid; gap:4px; margin-block:var(--wao-space-2); } .error { color:var(--wao-danger-strong); } .conflict,.skip-confirm { padding:var(--wao-space-2); border:1px solid var(--wao-line); border-radius:var(--wao-radius-control); background:var(--wao-bg-sub); margin-block:var(--wao-space-2); }
.footer-state { margin-right:auto; } input:focus-visible,select:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
@media(max-width:390px) { .two-fields { grid-template-columns:minmax(0,1fr); } }
@media(pointer:coarse) { input:not([type=checkbox]),select,.reminder-options label { min-height:44px; } }
</style>
