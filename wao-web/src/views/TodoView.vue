<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { useTaskLabelsStore } from '@/stores/taskLabels'
import { useMemberPreference } from '@/composables/memberPreference'
import { projectWorkItems } from '@/utils/projectWorkItems'
import { matchesTaskDate, type TaskDateFilter } from '@/utils/taskWorkspaceFilter'
import { isTaskFinished } from '@/utils/projectCompletion'
import { personalWorkItems } from '@/utils/personalWorkItems'
import ProjectChecklistView from '@/components/task/ProjectChecklistView.vue'
import TaskQuadrantView from '@/components/task/TaskQuadrantView.vue'
import TodoDayView from '@/components/todo/TodoDayView.vue'
import TodoLegacyView from './TodoLegacyView.vue'
const data = useDataStore(), session = useSessionStore(), ui = useUiStore(), labels = useTaskLabelsStore(), route = useRoute()
const selection = ref('all'), query = ref(''), layout = ref<'list' | 'quadrant'>('list'), collapsed = ref<string[]>([]), selectedDay = ref(data.today)
const nav = [{id:'all',name:'所有',icon:'ri-inbox-line'},{id:'today',name:'今天',icon:'ri-sun-line'},{id:'tomorrow',name:'明天',icon:'ri-sun-foggy-line'},{id:'week',name:'最近 7 天',icon:'ri-calendar-2-line'},{id:'inbox',name:'收集箱',icon:'ri-inbox-archive-line'}]
const saved = useMemberPreference<{id:string; name:string; selection:string; query:string}[]>('task-workspace-filters-v1', raw => { try { const v=JSON.parse(raw || '[]'); return Array.isArray(v) ? v.filter(x=>typeof x?.id==='string' && typeof x.name==='string' && typeof x.selection==='string' && typeof x.query==='string') : [] } catch { return [] } }, JSON.stringify)
watch(() => labels.identity, () => { selection.value='all'; query.value=''; collapsed.value=[]; void labels.load() }, { immediate:true })
watch(() => route.query.date, date => { if(typeof date==='string' && /^\d{4}-\d{2}-\d{2}$/.test(date)) { selectedDay.value=date; selection.value='review' } }, {immediate:true})
const projection = computed(() => projectWorkItems(data.tasks))
// 共享权限决定能否打开项目，个人工作台另按归属收敛，避免将可见任务当成本人待办。
const personal = computed(()=>personalWorkItems(projection.value.items, projection.value.refs, session.userId))
const scope = computed(()=>selection.value.startsWith('project:') ? projection.value.items.filter(t=>t.project===selection.value.slice(8)) : personal.value)
const visibleTags = computed(()=>[...new Set(personal.value.flatMap(task=>tagsFor(task.id)))].sort((a,b)=>a.localeCompare(b,'zh-CN')))
const tagsFor = (id:string) => { const ref=projection.value.refs[id]; return labels.get(ref?.taskId ?? id,ref?.subId).tags }
const filtered = computed(() => scope.value.filter(task => {
 if(selection.value==='completed') return isTaskFinished(task)
 if(isTaskFinished(task)) return false
 if(selection.value==='inbox' && task.project) return false
 if(selection.value.startsWith('project:') && task.project!==selection.value.slice(8)) return false
 if(selection.value.startsWith('tag:') && !tagsFor(task.id).includes(selection.value.slice(4))) return false
 if(['today','tomorrow','week'].includes(selection.value) && !matchesTaskDate(task,selection.value as TaskDateFilter,data.today)) return false
 return true
}))
const heading = computed(() => nav.find(x=>x.id===selection.value)?.name ?? (selection.value.startsWith('project:') ? data.projects.find(p=>p.id===selection.value.slice(8))?.name : selection.value.startsWith('tag:') ? '# '+selection.value.slice(4) : ({completed:'已完成',review:'每日回顾',legacy:'原待办视图'} as Record<string,string>)[selection.value]) ?? '任务')
const quick = ref(''), quickError = ref(''), creating = ref(false), quickId = ref('')
const draftKey = computed(() => `wao-todo-quick:${labels.identity}`)
watch(draftKey,()=>{ creating.value=false; quickError.value=''; try { const v=JSON.parse(localStorage.getItem(draftKey.value)||'null'); quick.value=v?.title||'';quickId.value=v?.id||'' } catch { quickError.value='无法读取新任务草稿' } },{immediate:true})
function protect() { try { quickId.value ||= crypto.randomUUID().replace(/-/g,''); localStorage.setItem(draftKey.value,JSON.stringify({title:quick.value,id:quickId.value})) } catch { quickError.value='无法保护草稿，请保持页面直到保存成功' } }
async function add() {
 if(!quick.value.trim() || creating.value) return
 creating.value=true;quickError.value='';protect()
 const identity=labels.identity, key=draftKey.value
 try {
  const existing=data.taskById(quickId.value)
  const result=existing ? (await data.flushTask(existing.id), {task:existing,persisted:data.taskSaveState(existing.id)==='saved'}) : await data.addTask({desc:'',title:quick.value.trim(),project:selection.value.startsWith('project:')?selection.value.slice(8):'',pr:'P2',owner:session.userId,due:selection.value==='tomorrow'?new Date(Date.parse(data.today+'T12:00:00Z')+86400000).toISOString().slice(0,10):selection.value==='today'?data.today:''},quickId.value)
  if(!result.persisted) throw new Error('任务未保存，请重试')
  localStorage.removeItem(key)
  if(identity===labels.identity){ quick.value='';quickId.value='' }
 } catch(e:any) { if(identity===labels.identity) quickError.value=e.message||'任务未保存，请重试' }
 finally { if(identity===labels.identity) creating.value=false }
}
function toggle(id:string) { collapsed.value=collapsed.value.includes(id)?collapsed.value.filter(x=>x!==id):[...collapsed.value,id] }
function saveFilter() { void saved.set([...saved.value.value,{id:crypto.randomUUID(),name:heading.value+(query.value?' · '+query.value:''),selection:selection.value,query:query.value}]) }
</script>
<template>
 <div class="task-workspace">
  <nav class="task-navigation" aria-label="任务导航">
   <button v-for="item in nav" :key="item.id" :class="{active:selection===item.id}" @click="selection=item.id"><i :class="item.icon"></i>{{ item.name }}</button>
   <p>清单</p><button v-for="project in data.projects" :key="project.id" :class="{active:selection==='project:'+project.id}" @click="selection='project:'+project.id"><i class="ri-list-check"></i>{{ project.name }}</button>
   <p>筛选器</p><button v-for="filter in saved.value.value" :key="filter.id" @click="selection=filter.selection==='assigned'?'all':filter.selection;query=filter.query"><i class="ri-filter-3-line"></i>{{ filter.name }}<span role="button" tabindex="0" aria-label="删除筛选器" @click.stop="saved.set(saved.value.value.filter(x=>x.id!==filter.id))" @keydown.enter.stop="saved.set(saved.value.value.filter(x=>x.id!==filter.id))">×</span></button>
   <button @click="saveFilter"><i class="ri-add-line"></i>保存当前筛选</button><small v-if="saved.error.value" role="alert">{{ saved.error.value }}<button @click="saved.retry()">重试</button></small>
   <p>标签</p><button v-for="tag in visibleTags" :key="tag" :class="{active:selection==='tag:'+tag}" @click="selection='tag:'+tag"><i class="ri-price-tag-3-line"></i>{{ tag }}</button>
   <small v-if="labels.error" role="alert">{{ labels.error }}<button @click="labels.load()">重试</button></small>
   <div class="nav-footer"><button :class="{active:selection==='completed'}" @click="selection='completed'"><i class="ri-checkbox-circle-line"></i>已完成</button><button :class="{active:selection==='review'}" @click="selection='review'"><i class="ri-history-line"></i>每日回顾</button><button @click="selection='legacy'"><i class="ri-layout-column-line"></i>原待办视图</button></div>
  </nav>
  <main class="workspace-main">
   <TodoDayView v-if="selection==='review'" v-model:date="selectedDay" @all="selection='all'" />
   <TodoLegacyView v-else-if="selection==='legacy'" />
   <template v-else>
    <header><h1>{{ heading }}</h1><span class="count">{{ filtered.length }}</span><div class="layout-switch"><button :aria-pressed="layout==='list'" @click="layout='list'">清单</button><button :aria-pressed="layout==='quadrant'" @click="layout='quadrant'">四象限</button><button @click="ui.openNewTask()">新建任务</button></div></header>
    <div class="workspace-tools"><input v-model="query" aria-label="搜索任务" placeholder="搜索任务及子任务" /><button @click="saveFilter">保存筛选</button></div>
    <form v-if="selection!=='completed'" class="quick-add" @submit.prevent="add"><i class="ri-add-line"></i><input v-model="quick" aria-label="添加任务" :placeholder="selection.startsWith('project:')?'添加任务到当前清单':'添加任务到收集箱'" :disabled="creating" @input="protect" /><button :disabled="creating || !quick.trim()">{{ creating?'保存中…':'添加' }}</button></form><p v-if="quickError" role="alert">{{ quickError }}</p>
    <p v-if="data.taskLoadState==='error'" role="alert">任务读取失败<button @click="data.hydrate()">重试</button></p>
    <ProjectChecklistView v-else-if="layout==='list'" :disable-hierarchy-drag="true" :items="filtered" :context-items="projection.items" :item-refs="projection.refs" :show-done="selection==='completed'" :collapsed="collapsed" :query="query" @toggle="toggle" @reveal="selection='all'" />
    <TaskQuadrantView v-else :tasks="filtered.filter(t=>!query || t.title.toLowerCase().includes(query.toLowerCase()))" :tag-options="[...new Set(scope.flatMap(task=>tagsFor(task.id)))]" :context-tasks="projection.items" :item-refs="projection.refs" :selected-ids="[]" :show-done="selection==='completed'" />
   </template>
  </main>
 </div>
</template>
<style scoped>
.task-workspace { display:grid; grid-template-columns:210px minmax(0,1fr); min-height:calc(100dvh - 110px); color:var(--wao-text); font-family:var(--wao-font); font-size:var(--wao-font-body); }
.task-navigation { padding:var(--wao-space-2); border-right:1px solid var(--wao-line); overflow:auto; max-height:calc(100dvh - 110px); }
button { font:inherit; color:inherit; border:0; border-radius:var(--wao-radius-control); background:transparent; cursor:pointer; }
.task-navigation>button,.nav-footer>button { display:flex; gap:var(--wao-space-1); align-items:center; text-align:left; width:100%; min-height:38px; padding:var(--wao-space-1); overflow-wrap:anywhere; }
.task-navigation p { color:var(--wao-text-3); font-size:var(--wao-font-caption); margin:var(--wao-space-3) var(--wao-space-1) var(--wao-space-1); }
button:hover { background:var(--wao-bg-hover); }
.active,button[aria-pressed=true] { background:var(--wao-primary-soft); color:var(--wao-primary-text); }
.nav-footer { border-top:1px solid var(--wao-line); margin-top:var(--wao-space-2); padding-top:var(--wao-space-2); }
.workspace-main { min-width:0; padding:var(--wao-space-3); }
header { display:flex; align-items:center; gap:var(--wao-space-1); margin-bottom:var(--wao-space-2); }
h1 { margin:0; font-size:22px; }
.count { color:var(--wao-text-3); }
.layout-switch { display:flex; gap:var(--wao-space-1); margin-left:auto; }
.layout-switch button,.workspace-tools button { padding:var(--wao-space-1); }
.workspace-tools { display:flex; gap:var(--wao-space-1); margin-bottom:var(--wao-space-2); }
input { min-width:0; color:var(--wao-text); background:var(--wao-bg-sub); font:inherit; padding:var(--wao-space-1); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); }
.workspace-tools input { flex:1; }
.quick-add { display:flex; align-items:center; gap:var(--wao-space-1); background:var(--wao-bg-sub); border-radius:var(--wao-radius-control); margin-bottom:var(--wao-space-2); padding:0 var(--wao-space-1); }
.quick-add input { border:0; background:transparent; flex:1; }
.quick-add button { color:var(--wao-primary-text); }
button:focus-visible,input:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
@media(max-width:1000px) { .task-workspace { grid-template-columns:160px minmax(0,1fr); }.workspace-main {padding:var(--wao-space-2);} }
@media(max-width:600px) { .task-workspace {display:block;} .task-navigation {max-height:200px; border-right:0;border-bottom:1px solid var(--wao-line);} header {flex-wrap:wrap;}button {min-height:44px;} }
</style>
