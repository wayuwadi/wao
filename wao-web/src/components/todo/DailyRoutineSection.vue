<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { saveTodoWithReceipt } from '@/api/dayWorkspace'
import { fetchRoutines, writeRoutine, writeOccurrence, type RoutineInput, type RoutineRange, type RoutineOccurrence, type RoutineStatus, type RoutineTemplate, type RoutineWrite, type OccurrenceWrite } from '@/api/routines'

const props = defineProps<{date:string}>()
const data = useDataStore(), session = useSessionStore()
const identity = () => `${session.account?.accountId || ''}:${session.userId}`
const range = ref<RoutineRange>(), error = ref(''), loading = ref(false), expanded = ref(true), managing = ref(false)
const shanghaiHour = Number(new Intl.DateTimeFormat('en-US',{timeZone:'Asia/Shanghai',hour:'numeric',hourCycle:'h23'}).format(new Date()))
const period = ref<'MORNING'|'EVENING'>(shanghaiHour < 14 ? 'MORNING':'EVENING')
const occurrences = computed(() => range.value?.days.find(day => day.date === props.date)?.occurrences || [])
const group = computed(() => occurrences.value.filter(item => item.period === period.value))
const writable = computed(() => props.date === data.today)
const pending = reactive<Record<string, boolean>>({}), itemErrors = reactive<Record<string,string>>({})
let sequence = 0
async function load() {
  const seq = ++sequence, date = props.date, member = identity()
  loading.value = true; error.value = ''
  try { const value = await fetchRoutines(date); if (seq === sequence && member === identity() && date === props.date) range.value = value }
  catch (cause) { if (seq === sequence) error.value = cause instanceof Error ? cause.message : '固定事项读取失败' }
  finally { if (seq === sequence) loading.value = false }
}
watch(() => [props.date,identity()], () => { range.value = undefined; void load() }, {immediate:true})
async function mark(item: RoutineOccurrence, status: RoutineStatus) {
  if (!writable.value || pending[item.templateId]) return
  const member = identity(), date = props.date
  pending[item.templateId] = true; itemErrors[item.templateId] = ''
  try {
    await saveTodoWithReceipt<OccurrenceWrite>(`routine:${item.templateId}:${date}`,member,{expectedVersion:item.version,status},
      (operation,body) => writeOccurrence(item.templateId,date,operation,body as {expectedVersion:number;status:RoutineStatus}),identity)
    if (member === identity()) await load()
  } catch (cause) {
    if (member === identity()) { itemErrors[item.templateId] = cause instanceof Error ? cause.message : '保存失败，请重试'; await load() }
  } finally { pending[item.templateId] = false }
}
const draft = reactive<RoutineInput>({expectedVersion:null,title:'',period:'MORNING',weekdays:[1,2,3,4,5,6,7],sortOrder:0,active:true,archived:false})
const draftId = ref(''), saving = ref(false), draftError = ref(''), savedMessage = ref('')
const draftKey = () => `wao-routine-draft-v1:${identity()}`
function newDraft() {
  Object.assign(draft,{expectedVersion:null,title:'',period:period.value,weekdays:[1,2,3,4,5,6,7],sortOrder:range.value?.templates.length || 0,active:true,archived:false})
  draftId.value = crypto.randomUUID(); savedMessage.value = ''; draftError.value = ''
}
function restore() {
  newDraft()
  try { const value = JSON.parse(localStorage.getItem(draftKey()) || 'null'); if (value?.id && value?.draft) { draftId.value=value.id; Object.assign(draft,value.draft) } }
  catch { draftError.value = '无法读取固定事项草稿，请保留当前输入' }
}
watch(identity, () => { managing.value=false; restore() },{immediate:true})
watch([draft, draftId], () => {
  if (!managing.value) return
  try { localStorage.setItem(draftKey(),JSON.stringify({id:draftId.value,draft})) }
  catch { draftError.value='无法保存本机草稿，请保持页面并保存' }
},{deep:true})
function manage() { managing.value = !managing.value; if (managing.value) restore() }
function edit(template:RoutineTemplate) {
  const revision = template.nextRevision || template.currentRevision
  if (!revision || saving.value) return
  Object.assign(draft,{expectedVersion:template.version,title:revision.title,period:revision.period,weekdays:[...revision.weekdays],sortOrder:revision.sortOrder,active:revision.active,archived:revision.archived})
  draftId.value=template.id; draftError.value=''; savedMessage.value=''
}
function useLatestRuleVersion() {
  const latest = range.value?.templates.find(item=>item.id===draftId.value)
  if (latest) { draft.expectedVersion=latest.version; draftError.value='已读取最新版本，当前输入保留；保存会替换明日起的规则' }
}
async function save() {
  if (saving.value || !draft.title.trim() || !draft.weekdays.length) return
  const member=identity(), submitted=JSON.parse(JSON.stringify(draft)) as RoutineInput, id=draftId.value
  saving.value=true; draftError.value=''; savedMessage.value=''
  try {
    const result = await saveTodoWithReceipt<RoutineWrite>(`routine-rule:${id}`,member,submitted,(op,body)=>writeRoutine(id,op,body as RoutineInput),identity)
    if (member !== identity()) return
    if (JSON.stringify(draft) === JSON.stringify(submitted)) { localStorage.removeItem(draftKey()); newDraft() }
    savedMessage.value=`已保存，${result.effectiveFrom === data.today ? '今天就可以做':'从 '+result.effectiveFrom+' 生效，今天和历史保持原样'}`
    await load()
  } catch (cause) {
    if (member===identity()) { draftError.value=cause instanceof Error?cause.message:'保存失败，草稿已保留'; await load() }
  } finally { saving.value=false }
}
</script>

<template>
  <section class="routine-section" aria-label="每日固定事项">
    <header><button type="button" :aria-expanded="expanded" @click="expanded=!expanded">{{ expanded?'⌄':'›' }} 每日固定 <small>{{ occurrences.filter(item=>item.status==='DONE').length }}/{{ occurrences.length }}</small></button><button type="button" :aria-expanded="managing" @click="manage">{{ managing?'收起管理':'管理' }}</button></header>
    <template v-if="expanded">
      <p v-if="loading && !range" role="status">正在读取固定事项…</p><p v-if="error" role="alert">固定事项暂时无法读取：{{ error }} <button type="button" @click="load">重新加载</button></p>
      <template v-else-if="range"><nav aria-label="固定事项时段"><button type="button" :aria-pressed="period==='MORNING'" @click="period='MORNING'">☀ 早晨</button><button type="button" :aria-pressed="period==='EVENING'" @click="period='EVENING'">☾ 晚上</button></nav>
        <p v-if="!writable" class="muted">{{ date<data.today?'当天记录只读':'先看计划，完成与跳过留给当天' }}</p>
        <p v-if="!group.length" class="muted">这个时段还没有固定事项。<button v-if="writable" type="button" @click="managing=true; restore()">添加一项</button></p>
        <div v-for="item in group" :key="item.templateId" class="routine-row"><button type="button" class="routine-check" :disabled="!writable || pending[item.templateId]" :aria-pressed="item.status==='DONE'" :aria-label="`完成固定事项：${item.title}`" @click="mark(item,item.status==='DONE'?'OPEN':'DONE')">{{ item.status==='DONE'?'✓':item.status==='SKIPPED'?'−':'○' }}</button><span>{{ item.title }}<small v-if="item.status==='SKIPPED'">今天跳过</small></span><button v-if="writable" type="button" :disabled="pending[item.templateId]" @click="mark(item,item.status==='OPEN'?'SKIPPED':'OPEN')">{{ pending[item.templateId]?'保存中…':item.status==='OPEN'?'跳过':'恢复未做' }}</button><p v-if="itemErrors[item.templateId]" role="alert">{{ itemErrors[item.templateId] }}</p></div>
      </template>
    </template>
    <div v-if="managing" class="routine-manager" @keydown.esc.stop="managing=false">
      <p class="muted">新事项今天出现，已有规则修改明天生效。不把没做的事项滚成欠账。</p>
      <div class="rule-list"><button v-for="item in range?.templates" :key="item.id" type="button" @click="edit(item)">{{ (item.nextRevision || item.currentRevision)?.title }}<small>{{ item.nextRevision?'明天有新规则':item.currentRevision?.archived?'已归档':item.currentRevision?.active?'':'已暂停' }}</small></button></div>
      <form @submit.prevent="save"><label>事项<input v-model="draft.title" aria-label="固定事项标题" :disabled="saving" maxlength="120" /></label><label>时段<select v-model="draft.period" :disabled="saving"><option value="MORNING">早晨</option><option value="EVENING">晚上</option></select></label><fieldset><legend>在哪些天做</legend><label v-for="(name,index) in ['一','二','三','四','五','六','日']" :key="index"><input v-model="draft.weekdays" type="checkbox" :value="index+1" :disabled="saving" />周{{ name }}</label></fieldset><label>顺序<input v-model.number="draft.sortOrder" type="number" min="0" max="999" :disabled="saving" /></label><label><input v-model="draft.active" type="checkbox" :disabled="saving" />启用</label><label v-if="draft.expectedVersion!==null"><input v-model="draft.archived" type="checkbox" :disabled="saving" />归档（保留历史）</label><div class="rule-actions"><button type="submit" :disabled="saving || !draft.title.trim() || !draft.weekdays.length">{{ saving?'保存中…':draft.expectedVersion===null?'添加固定事项':'保存明日起的规则' }}</button><button type="button" :disabled="saving" @click="newDraft">新增另一项</button></div><p v-if="draftError" role="alert">{{ draftError }} <button v-if="draft.expectedVersion!==null" type="button" @click="useLatestRuleVersion">基于最新规则保留输入</button></p><p v-if="savedMessage" role="status">{{ savedMessage }}</p></form>
    </div>
  </section>
</template>

<style scoped>
.routine-section{margin:14px 0;padding:12px 16px;border:1px solid var(--wao-line-control);border-radius:12px;background:var(--wao-bg-card)}header{display:flex;justify-content:space-between;gap:8px}button{font:inherit;cursor:pointer;color:var(--wao-primary);background:none;border:0;padding:6px}header>button:first-child{font-weight:650}small,.muted{font-size:12px;color:var(--wao-text-3)}small{margin-left:8px}nav{display:flex;gap:12px;margin:10px 0}nav button[aria-pressed=true]{background:var(--wao-bg-hover);border-radius:7px}.routine-row{display:flex;flex-wrap:wrap;align-items:center;gap:8px;padding:4px 0}.routine-row>span{flex:1;min-width:0;overflow-wrap:anywhere}.routine-check{font-size:20px;min-width:30px}.routine-row>p{width:100%;font-size:12px;color:var(--wao-danger)}.routine-manager{margin-top:12px;border-top:1px solid var(--wao-line-control);padding-top:10px}.rule-list{display:flex;flex-wrap:wrap;gap:6px}.rule-list button{border:1px solid var(--wao-line-control);border-radius:7px;text-align:left;overflow-wrap:anywhere}form{display:flex;flex-wrap:wrap;gap:12px;margin-top:12px}form>label{display:flex;align-items:center;gap:6px;max-width:100%;font-size:12px}input,select{min-width:0;max-width:100%;box-sizing:border-box;border:1px solid var(--wao-line-control);border-radius:6px;padding:7px;background:var(--wao-bg-card);color:var(--wao-text-1);font:inherit}input[type=number]{width:70px}fieldset{display:flex;gap:8px;flex-wrap:wrap;border:1px solid var(--wao-line-control);border-radius:6px;font-size:12px}.rule-actions{display:flex;flex-wrap:wrap;gap:10px;width:100%}.rule-actions button{border:1px solid var(--wao-line-control);border-radius:7px}form p{width:100%;margin:0;font-size:12px}button:disabled{opacity:.55;cursor:default}button:focus-visible,input:focus-visible,select:focus-visible{outline:2px solid var(--wao-primary);outline-offset:2px}@media(max-width:600px){button{min-height:44px}.routine-section{padding:8px}fieldset label{display:flex;align-items:center;min-height:44px}form>label:first-child{width:100%}form>label:first-child input{flex:1}}
</style>
