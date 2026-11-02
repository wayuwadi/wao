<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { searchProjectTasks } from '@/utils/projectTaskSearch'
import { useTodoTaskMove } from '@/composables/todoTaskMove'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { useTaskViewMemory } from '@/composables/taskViewMemory'
import { useRoute, useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useTaskLabelsStore } from '@/stores/taskLabels'
import { projectWorkItems } from '@/utils/projectWorkItems'
import { matchesTaskDate, type TaskDateFilter } from '@/utils/taskWorkspaceFilter'
import { isTaskFinished } from '@/utils/projectCompletion'
import { personalWorkItems } from '@/utils/personalWorkItems'
import ProjectChecklistView from '@/components/task/ProjectChecklistView.vue'
import TaskQuadrantView from '@/components/task/TaskQuadrantView.vue'
import TodoDayView from '@/components/todo/TodoDayView.vue'
import ContextActionMenu from '@/components/ui/ContextActionMenu.vue'
import TaskListDialog from '@/components/todo/TaskListDialog.vue'
import ProjectSettingsModal from '@/components/overlays/ProjectSettingsModal.vue'
import type { Project } from '@/types'
import TodayDates from '@/components/routines/TodayDates.vue'
const data = useDataStore(), session = useSessionStore(), labels = useTaskLabelsStore(), route = useRoute(), router = useRouter()
const memory = useTaskViewMemory('todo', () => ({ navigationCollapsed: false, selection: 'all', layout: 'list' as 'list'|'quadrant'|'table' }))
const selection = computed({get:()=>memory.value.selection === 'legacy' ? 'all' : memory.value.selection,set:v=>{memory.value.selection=v; if (route.query.list) void router.replace({query:{...route.query,list:undefined}})}})
const navigationCollapsed = computed({get:()=>!!memory.value.navigationCollapsed,set:v=>{memory.value.navigationCollapsed=v}})
const layout = computed({get:()=>memory.value.layout === 'table' ? 'list' : memory.value.layout,set:v=>{memory.value.layout=v}})
const scopeMemory = useTaskViewMemory(() => `todo:${selection.value}`, () => ({query:'',collapsed:[] as string[]}))
const query = computed({get:()=>scopeMemory.value.query,set:v=>{scopeMemory.value.query=v}})
const collapsed = computed({get:()=>scopeMemory.value.collapsed,set:v=>{scopeMemory.value.collapsed=v}})
const listDialog = ref(false), editingList = ref<Project>(), settingsList = ref<Project>()
const listMenu = ref<InstanceType<typeof ContextActionMenu>>()
function openListMenu(project: Project, event: MouseEvent) {
 void listMenu.value?.openAt(event, project.name, [
  {id:'rename',label:'重命名清单',run:()=>editList(project)},
  {id:'settings',label:'清单设置',run:()=>{settingsList.value=project}},
 ], event.currentTarget as HTMLElement)
}
function editList(project?: Project) { editingList.value = project; listDialog.value = true }
function listSaved(id: string) { listDialog.value = false; selection.value = 'project:' + id }
watch(() => route.query.list, id => { if (typeof id === 'string' && id) memory.value.selection = 'project:' + id }, { immediate:true })
watch(() => route.query.layout, value => { if (['list','table','quadrant'].includes(String(value))) layout.value = value === 'quadrant' ? 'quadrant' : 'list' }, { immediate:true })
const selectedDay = ref(data.today)
const nav = [{id:'all',name:'所有',icon:'ri-inbox-line'},{id:'today',name:'今天',icon:'ri-sun-line'},{id:'tomorrow',name:'明天',icon:'ri-sun-foggy-line'},{id:'week',name:'最近 7 天',icon:'ri-calendar-2-line'},{id:'inbox',name:'收集箱',icon:'ri-inbox-archive-line'}]
watch(() => labels.identity, () => { void labels.load() }, { immediate:true })
watch(() => route.query.date, date => { if(typeof date==='string' && /^\d{4}-\d{2}-\d{2}$/.test(date)) { selectedDay.value=date; selection.value='review' } }, {immediate:true})
const order = useTaskOrderStore()
const orderScope = computed(() => selection.value.startsWith('project:') ? selection.value : `todo:${selection.value}`)
watch([orderScope, () => session.userId], () => { void order.load(orderScope.value) }, { immediate:true })
const projection = computed(() => {
 const result = projectWorkItems(data.tasks)
 return { ...result, items: order.sort(orderScope.value, result.items, task => task.id) }
})
const moving = useTodoTaskMove(() => projection.value, () => orderScope.value)
async function move(source:string,target:string|null,side:'before'|'inside'|'after') {
 await moving.move(source,target,side)
 if (!moving.error.value && target) collapsed.value=collapsed.value.filter(id=>id!==target)
}
// 共享权限决定能否打开项目，个人工作台另按归属收敛，避免将可见任务当成本人待办。
const personal = computed(()=>personalWorkItems(projection.value.items, projection.value.refs, session.userId))
const scope = computed(()=>selection.value.startsWith('project:') ? projection.value.items.filter(t=>t.project===selection.value.slice(8)) : personal.value)
const visibleTags = computed(()=>[...new Set(personal.value.flatMap(task=>tagsFor(task.id)))].sort((a,b)=>a.localeCompare(b,'zh-CN')))
const tagsFor = (id:string) => { const ref=projection.value.refs[id]; return labels.get(ref?.taskId ?? id,ref?.subId).tags }
const filtered = computed(() => scope.value.filter(task => {
 if(selection.value==='completed' && !query.value.trim()) return isTaskFinished(task)
 const sourceId=projection.value.refs[task.id]?.taskId??task.id
 if(!query.value.trim() && isTaskFinished(task) && !['saving','error'].includes(data.taskSaveState(sourceId))) return false
 if(selection.value==='inbox' && task.project) return false
 if(selection.value.startsWith('project:') && task.project!==selection.value.slice(8)) return false
 if(selection.value.startsWith('tag:') && !tagsFor(task.id).includes(selection.value.slice(4))) return false
 if(['today','tomorrow','week'].includes(selection.value) && !matchesTaskDate(task,selection.value as TaskDateFilter,data.today)) return false
 return true
}))
const searched = computed(()=>query.value ? searchProjectTasks(filtered.value,query.value).map(hit=>hit.task) : filtered.value)
const heading = computed(() => nav.find(x=>x.id===selection.value)?.name ?? (selection.value.startsWith('project:') ? data.projects.find(p=>p.id===selection.value.slice(8))?.name : selection.value.startsWith('tag:') ? '# '+selection.value.slice(4) : ({completed:'已完成',review:'每日回顾',legacy:'所有'} as Record<string,string>)[selection.value]) ?? '任务')
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
  const result=existing ? (await data.flushTask(existing.id), {task:existing,persisted:data.taskSaveState(existing.id)==='saved'}) : await data.addTask({desc:'',title:quick.value.trim(),project:selection.value.startsWith('project:')?selection.value.slice(8):'',pr:'P2',owner:session.userId,due:''},quickId.value)
  if(!result.persisted) throw new Error('任务未保存，请重试')
  localStorage.removeItem(key)
  if(identity===labels.identity){ quick.value='';quickId.value='' }
 } catch(e:any) { if(identity===labels.identity) quickError.value=e.message||'任务未保存，请重试' }
 finally { if(identity===labels.identity) creating.value=false }
}
function toggle(id:string) { collapsed.value=collapsed.value.includes(id)?collapsed.value.filter(x=>x!==id):[...collapsed.value,id] }
</script>
<template>
 <div class="task-workspace" :class="{'navigation-collapsed':navigationCollapsed,'matrix-workspace':layout==='quadrant' && selection!=='review'}">
  <nav v-show="!navigationCollapsed" id="todo-navigation" class="task-navigation" aria-label="任务导航">
   <button v-for="item in nav" :key="item.id" :class="{active:selection===item.id}" @click="selection=item.id"><i :class="item.icon"></i>{{ item.name }}</button>
   <div class="list-heading"><p>清单</p><button aria-label="新建清单" @click="editList()"><i class="ri-add-line" /></button></div>
   <div v-for="project in data.projects" :key="project.id" class="list-entry">
    <button :class="{active:selection==='project:'+project.id}" @click="selection='project:'+project.id"><i class="ri-list-check"></i><span>{{ project.name }}</span></button>
    <button :aria-label="`清单操作 ${project.name}`" @click="openListMenu(project,$event)"><i class="ri-more-line" /></button>
   </div>
   <p>标签</p><button v-for="tag in visibleTags" :key="tag" :class="{active:selection==='tag:'+tag}" @click="selection='tag:'+tag"><i class="ri-price-tag-3-line"></i>{{ tag }}</button>
   <small v-if="labels.error" role="alert">{{ labels.error }}<button @click="labels.load()">重试</button></small>
   <div class="nav-footer"><button :class="{active:selection==='completed'}" @click="selection='completed'"><i class="ri-checkbox-circle-line"></i>已完成</button><button :class="{active:selection==='review'}" @click="selection='review'"><i class="ri-history-line"></i>每日回顾</button></div>
  </nav>
  <main class="workspace-main">
   <button v-if="selection==='review'" :aria-expanded="!navigationCollapsed" aria-controls="todo-navigation" @click="navigationCollapsed=!navigationCollapsed">{{navigationCollapsed ? '展开待办导航' : '收起待办导航'}}</button>
   <TodoDayView v-if="selection==='review'" v-model:date="selectedDay" @all="selection='all'" />
   <template v-else>
    <header><button class="navigation-toggle" :aria-label="navigationCollapsed ? '展开待办导航' : '收起待办导航'" :title="navigationCollapsed ? '展开待办导航' : '收起待办导航'" :aria-expanded="!navigationCollapsed" aria-controls="todo-navigation" @click="navigationCollapsed=!navigationCollapsed"><i :class="navigationCollapsed ? 'ri-side-bar-line' : 'ri-layout-left-line'" /></button><h1>{{ heading }}</h1><span class="count">{{ searched.length }}</span><div class="workspace-tools"><input v-model="query" aria-label="搜索任务" placeholder="搜索任务及子任务" /></div><div class="layout-switch"><button :aria-pressed="layout==='list'" @click="layout='list'">清单</button><button :aria-pressed="layout==='quadrant'" @click="layout='quadrant'">四象限</button></div></header>
    <p v-if="moving.error.value || order.state(orderScope).error || order.state(orderScope).dirty" class="move-error" role="alert">{{moving.error.value || order.state(orderScope).error || '顺序尚未保存'}}<button v-if="order.state(orderScope).error || order.state(orderScope).dirty" @click="order.retry(orderScope)">重试保存顺序</button></p>
    <TodayDates v-if="selection==='today'" />
    <form v-if="selection!=='completed'" class="quick-add" @submit.prevent="add"><i class="ri-add-line"></i><input v-model="quick" aria-label="添加任务" :placeholder="selection.startsWith('project:')?'添加任务到当前清单':'添加任务到收集箱'" :disabled="creating" @input="protect" /><button :disabled="creating || !quick.trim()">{{ creating?'保存中…':'添加' }}</button></form><p v-if="quickError" role="alert">{{ quickError }}</p>
    <p v-if="data.taskLoadState==='error'" role="alert">任务读取失败<button @click="data.hydrate()">重试</button></p>
    <ProjectChecklistView hide-groups v-else-if="layout==='list'" :key="selection" :state-key="`todo:${selection}:list`" :busy="moving.busy.value" @move="move" :items="filtered" :context-items="projection.items" :item-refs="projection.refs" :show-done="selection==='completed' || !!query.trim()" :collapsed="collapsed" :query="query" @toggle="toggle" @reveal="selection='all'" />
    <TaskQuadrantView :selected-ids="[]" v-else :key="selection" :state-key="`todo:${selection}:quadrant`" :tasks="searched" :tag-options="[...new Set(scope.flatMap(task=>tagsFor(task.id)))]" :context-tasks="projection.items" :item-refs="projection.refs" :show-done="selection==='completed' || !!query.trim()" />
   </template>
  </main>
  <ContextActionMenu ref="listMenu" />
  <TaskListDialog v-if="listDialog" :project="editingList" @close="listDialog=false" @saved="listSaved" />
  <ProjectSettingsModal v-if="settingsList" :project="settingsList" @close="settingsList=undefined" @deleted="settingsList=undefined;selection='all'" />
 </div>
</template>
<style scoped>
.task-workspace.navigation-collapsed {grid-template-columns:minmax(0,1fr);}
.navigation-toggle {display:inline-flex;align-items:center;justify-content:center;min-width:var(--wao-control-height);min-height:var(--wao-control-height);color:var(--wao-text-3);}

.list-heading,.list-entry { display:flex; align-items:center; gap:var(--wao-space-1); }
.list-heading { justify-content:space-between; }
.list-entry>button:first-child { display:flex; align-items:center; gap:var(--wao-space-1); flex:1; min-width:0; text-align:left; }
.list-entry>button:first-child span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.list-entry>button { min-height:38px; padding:var(--wao-space-1); }

.task-workspace { display:grid; grid-template-columns:210px minmax(0,1fr); min-height:calc(100dvh - 110px); color:var(--wao-text); font-family:var(--wao-font); font-size:var(--wao-font-body); }
.task-navigation { padding:var(--wao-space-2); border-right:1px solid var(--wao-line); overflow:auto; max-height:calc(100dvh - 110px); }
button { font:inherit; color:inherit; border:0; border-radius:var(--wao-radius-control); background:transparent; cursor:pointer; }
.task-navigation>button,.nav-footer>button { display:flex; gap:var(--wao-space-1); align-items:center; text-align:left; width:100%; min-height:38px; padding:var(--wao-space-1); overflow-wrap:anywhere; }
.task-navigation p { color:var(--wao-text-3); font-size:var(--wao-font-caption); margin:var(--wao-space-3) var(--wao-space-1) var(--wao-space-1); }
button:hover { background:var(--wao-bg-hover); }
.active,button[aria-pressed=true] { background:var(--wao-primary-soft); color:var(--wao-primary-text); }
.nav-footer { border-top:1px solid var(--wao-line); margin-top:var(--wao-space-2); padding-top:var(--wao-space-2); }
.workspace-main { min-width:0; padding:var(--wao-space-3); }
.batch-toolbar { display:flex; gap:var(--wao-space-1); align-items:center; flex-wrap:wrap; margin-bottom:var(--wao-space-1); flex-shrink:0; }
header { display:flex; flex-wrap:wrap; align-items:center; gap:var(--wao-space-1); margin-bottom:var(--wao-space-2); }
h1 { margin:0; font-size: var(--wao-font-title);  font-weight: var(--wao-weight-heading); line-height: var(--wao-line-heading);}
.count { color:var(--wao-text-3); }
.layout-switch { display:flex; flex-wrap:wrap; gap:var(--wao-space-1); flex-shrink:0; max-width:100%; }
.layout-switch button,.workspace-tools button { padding:var(--wao-space-1); }
.workspace-tools { display:flex; gap:var(--wao-space-1); margin-left:auto; min-width:0; }
input { min-width:0; color:var(--wao-text); background:var(--wao-bg-sub); font:inherit; padding:var(--wao-space-1); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); }
.workspace-tools input { width:200px; max-width:100%; }
.quick-add { display:flex; align-items:center; gap:var(--wao-space-1); background:var(--wao-bg-sub); border-radius:var(--wao-radius-control); margin-bottom:var(--wao-space-2); padding:0 var(--wao-space-1); }
.quick-add input { border:0; background:transparent; flex:1; }
.quick-add button { color:var(--wao-primary-text); }
button:focus-visible,input:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
.matrix-workspace { flex:1; width:100%; min-height:0; overflow:hidden; }
.matrix-workspace .workspace-main { display:flex; flex-direction:column; min-height:0; overflow:hidden; }
.matrix-workspace .workspace-main>header,.matrix-workspace .quick-add { flex-shrink:0; }
.matrix-workspace .task-navigation { min-height:0; max-height:none; }
@media(max-width:1000px) { .task-workspace { grid-template-columns:160px minmax(0,1fr); }.workspace-main {padding:var(--wao-space-2);} }
@media(max-width:600px) { .task-workspace {display:block;} .task-navigation {max-height:200px; border-right:0;border-bottom:1px solid var(--wao-line);} header {flex-wrap:wrap;} .workspace-tools {margin-left:0;flex:1;} .workspace-tools input {width:140px;} .workspace-tools button {display:none;} .layout-switch {margin-left:auto;} .matrix-workspace {display:flex;flex-direction:column;} .matrix-workspace .task-navigation {max-height:120px;flex-shrink:0;} .matrix-workspace .workspace-main {flex:1;}button {min-height:44px;} }
</style>
