<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useTodoTaskMove } from '@/composables/todoTaskMove'
import { useTaskOrderStore } from '@/stores/taskOrder'
import { useTaskViewMemory } from '@/composables/taskViewMemory'
import { useTodoViewPreferences } from '@/composables/todoViewPreferences'
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
import TodoListHeader from '@/components/todo/TodoListHeader.vue'
import OverdueRescheduleDialog from '@/components/todo/OverdueRescheduleDialog.vue'
import { toast } from '@/composables/toast'
import { TODO_VIEWS, type TodoLayout, type TodoViewSettings } from '@/utils/todoViewSettings'
const reschedule = ref(false)
const data = useDataStore(), session = useSessionStore(), labels = useTaskLabelsStore(), route = useRoute(), router = useRouter()
const memory = useTaskViewMemory('todo', () => ({ navigationCollapsed: false, selection: 'all' }))
const routeLayout = ref<TodoLayout>()
const selection = computed({get:()=>memory.value.selection === 'legacy' ? 'all' : memory.value.selection,set:v=>{if(v!==memory.value.selection)routeLayout.value=undefined; memory.value.selection=v; if (route.query.list || route.query.layout || route.query.scope) void router.replace({query:{...route.query,list:undefined,layout:undefined,scope:undefined}})}})
const navigationCollapsed = computed({get:()=>!!memory.value.navigationCollapsed,set:v=>{memory.value.navigationCollapsed=v}})
const { view, display, changeView, changeDisplay, error: preferenceError, retry: retryPreferences } = useTodoViewPreferences(selection)
watch(() => route.query.layout, value => { routeLayout.value = value === 'table' ? 'list' : TODO_VIEWS.find(item => item.id === value)?.id }, { immediate:true })
const layout = computed(() => {
 const mode = routeLayout.value ?? view.value.layout
 return selection.value === 'abandoned' && mode === 'quadrant' ? 'list' : mode
})
const activeView = computed(() => ({ ...view.value, layout: layout.value }))
function updateView(patch: Partial<TodoViewSettings>) {
 if (patch.layout) {
  routeLayout.value = undefined
  if (route.query.layout) void router.replace({query:{...route.query,layout:undefined}})
 }
 changeView(patch)
}
const scopeMemory = useTaskViewMemory(() => `todo:${selection.value}`, () => ({collapsed:[] as string[]}))
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
const selectedDay = ref(data.today)
const nav = [{id:'all',name:'所有',icon:'ri-inbox-line'},{id:'today',name:'今天',icon:'ri-sun-line'},{id:'tomorrow',name:'明天',icon:'ri-sun-foggy-line'},{id:'week',name:'最近 7 天',icon:'ri-calendar-2-line'},{id:'inbox',name:'收集箱',icon:'ri-inbox-archive-line'}]
watch(() => route.query.scope, value => { if(!route.query.list && nav.some(item=>item.id===value)) memory.value.selection=String(value) }, { immediate:true })
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
 if(selection.value==='abandoned') return task.status==='已放弃'
 if(task.status==='已放弃') return false
 if(task.workItemType==='note' && (['timeline','quadrant'].includes(layout.value)||['today','tomorrow','week','completed'].includes(selection.value)))return false
 if(selection.value==='completed') return isTaskFinished(task)
 const sourceId=projection.value.refs[task.id]?.taskId??task.id
 if(!view.value.showDone && isTaskFinished(task) && !['saving','error'].includes(data.taskSaveState(sourceId))) return false
 if(selection.value==='inbox' && task.project) return false
 if(selection.value.startsWith('project:') && task.project!==selection.value.slice(8)) return false
 if(selection.value.startsWith('tag:') && !tagsFor(task.id).includes(selection.value.slice(4))) return false
 if(['today','tomorrow','week'].includes(selection.value) && !matchesTaskDate(task,selection.value as TaskDateFilter,data.today)) return false
 return true
}))
const searched = filtered
const heading = computed(() => nav.find(x=>x.id===selection.value)?.name ?? (selection.value.startsWith('project:') ? data.projects.find(p=>p.id===selection.value.slice(8))?.name : selection.value.startsWith('tag:') ? '# '+selection.value.slice(4) : ({completed:'已完成',abandoned:'已放弃',review:'每日回顾',legacy:'所有'} as Record<string,string>)[selection.value]) ?? '任务')
const destinationProject = computed(() => selection.value.startsWith('project:') ? data.projectById(selection.value.slice(8)) : undefined)
const destinationName = computed(() => destinationProject.value?.name ?? (selection.value.startsWith('project:') ? '当前清单' : '收集箱'))
const showDone = computed(() => selection.value==='completed' || view.value.showDone)
const quick = ref(''), quickError = ref(''), creating = ref(false), quickId = ref('')
const draftKey = computed(() => `wao-todo-quick:${labels.identity}`)
watch(draftKey,()=>{ creating.value=false; quickError.value=''; try { const v=JSON.parse(localStorage.getItem(draftKey.value)||'null'); quick.value=v?.title||'';quickId.value=v?.id||'' } catch { quickError.value='无法读取新任务草稿' } },{immediate:true})
function protect() { try { quickId.value ||= crypto.randomUUID().replace(/-/g,''); localStorage.setItem(draftKey.value,JSON.stringify({title:quick.value,id:quickId.value})) } catch { quickError.value='无法保护草稿，请保持页面直到保存成功' } }
async function add() {
 if(!quick.value.trim() || creating.value) return
 if(selection.value.startsWith('project:') && !destinationProject.value) { quickError.value='当前清单暂不可用，请重新选择或存入收集箱'; return }
 creating.value=true;quickError.value='';protect()
 const identity=labels.identity, key=draftKey.value
 try {
  const existing=data.taskById(quickId.value)
  const result=existing ? (await data.flushTask(existing.id), {task:existing,persisted:data.taskSaveState(existing.id)==='saved'}) : await data.addTask({desc:'',title:quick.value.trim(),project:destinationProject.value?.id ?? '',pr:'P2',owner:session.userId,due:''},quickId.value)
  if(!result.persisted) throw new Error('任务未保存，请重试')
  localStorage.removeItem(key)
  if(identity===labels.identity){ quick.value='';quickId.value='' }
 } catch(e:any) { if(identity===labels.identity) quickError.value=e.message||'任务未保存，请重试' }
 finally { if(identity===labels.identity) creating.value=false }
}
function toggle(id:string) { collapsed.value=collapsed.value.includes(id)?collapsed.value.filter(x=>x!==id):[...collapsed.value,id] }
</script>
<template>
 <div class="task-workspace" :class="{'navigation-collapsed':navigationCollapsed,'bounded-workspace':selection!=='review','matrix-workspace':layout==='quadrant' && selection!=='review'}">
  <nav v-show="!navigationCollapsed" id="todo-navigation" class="task-navigation" aria-label="任务导航">
   <button v-for="item in nav" :key="item.id" :class="{active:selection===item.id}" @click="selection=item.id"><i :class="item.icon" aria-hidden="true"></i>{{ item.name }}</button>
   <div class="list-heading"><p>清单</p><button aria-label="新建清单" @click="editList()"><i class="ri-add-line" /></button></div>
   <div v-for="project in data.projects" :key="project.id" class="list-entry">
    <button :class="{active:selection==='project:'+project.id}" @click="selection='project:'+project.id"><i class="ri-list-check" aria-hidden="true"></i><span>{{ project.name }}</span></button>
    <button :aria-label="`清单操作 ${project.name}`" @click="openListMenu(project,$event)"><i class="ri-more-line" /></button>
   </div>
   <p>标签</p><button v-for="tag in visibleTags" :key="tag" :class="{active:selection==='tag:'+tag}" @click="selection='tag:'+tag"><i class="ri-price-tag-3-line" aria-hidden="true"></i>{{ tag }}</button>
   <small v-if="labels.error" role="alert">{{ labels.error }}<button @click="labels.load()">重试</button></small>
   <div class="nav-footer"><button :class="{active:selection==='abandoned'}" @click="selection='abandoned'"><i class="ri-close-circle-line"></i>已放弃</button><button :class="{active:selection==='completed'}" @click="selection='completed'"><i class="ri-checkbox-circle-line"></i>已完成</button><button :class="{active:selection==='review'}" @click="selection='review'"><i class="ri-history-line"></i>每日回顾</button></div>
  </nav>
  <main class="workspace-main">
   <button v-if="selection==='review'" :aria-expanded="!navigationCollapsed" aria-controls="todo-navigation" @click="navigationCollapsed=!navigationCollapsed">{{navigationCollapsed ? '展开待办导航' : '收起待办导航'}}</button>
   <TodoDayView v-if="selection==='review'" v-model:date="selectedDay" @all="selection='all'" />
   <template v-else>
    <TodoListHeader v-if="layout==='quadrant' || data.taskLoadState==='error'" v-model:quick="quick" :view="activeView" :display="display" :sync-error="preferenceError" :destination="destinationName" :heading="heading" :count="searched.length" :selection="selection" :navigation-collapsed="navigationCollapsed" :creating="creating" :error="quickError" @view="updateView" @display="changeDisplay" @retry="retryPreferences" @navigation="navigationCollapsed=!navigationCollapsed" @add="add" @protect="protect" />
    <p v-if="preferenceError" class="move-error" role="status">{{preferenceError}} <button @click="retryPreferences()">重试同步视图</button></p>
    <p v-if="moving.error.value || order.state(orderScope).error || order.state(orderScope).dirty" class="move-error" role="alert">{{moving.error.value || order.state(orderScope).error || '顺序尚未保存'}}<button v-if="order.state(orderScope).error || order.state(orderScope).dirty" @click="order.retry(orderScope)">重试保存顺序</button></p>
    <p v-if="data.taskLoadState==='error'" role="alert">任务读取失败<button @click="data.hydrate()">重试</button></p>
    <ProjectChecklistView :hide-list-name="!!destinationProject" hide-groups :group-overdue="selection==='today'" v-else-if="layout!=='quadrant'" :key="selection" :state-key="`todo:${selection}:list`" :busy="moving.busy.value" @move="move" :items="filtered" :show-abandoned="selection==='abandoned'" :context-items="projection.items" :item-refs="projection.refs" :show-done="showDone" :collapsed="collapsed" :todo-view="activeView" :todo-display="display" :todo-tags="tagsFor" query="" @toggle="toggle" @reveal="selection='all'">
     <template #list-header><TodoListHeader v-model:quick="quick" :view="activeView" :display="display" :sync-error="preferenceError" :destination="destinationName" :heading="heading" :count="searched.length" :selection="selection" :navigation-collapsed="navigationCollapsed" :creating="creating" :error="quickError" @view="updateView" @display="changeDisplay" @retry="retryPreferences" @navigation="navigationCollapsed=!navigationCollapsed" @add="add" @protect="protect" /></template>
     <template #section-action="{section}"><button v-if="selection==='today' && section.id==='overdue'" class="reschedule-button" @click="reschedule=true">顺延</button></template>
    </ProjectChecklistView>
    <TaskQuadrantView :add-project-id="destinationProject?.id ?? ''" :hide-list-name="!!destinationProject" :selected-ids="[]" v-else :key="selection" :state-key="`todo:${selection}:quadrant`" :tasks="searched" :tag-options="[...new Set(scope.flatMap(task=>tagsFor(task.id)))]" :context-tasks="projection.items" :item-refs="projection.refs" :show-done="showDone" />
   </template>
  </main>
  <OverdueRescheduleDialog v-if="reschedule" :items="searched" :item-refs="projection.refs" @close="reschedule=false" @saved="reschedule=false;toast(`已处理 ${$event} 项逾期任务`,'ri-check-line')" />
  <ContextActionMenu ref="listMenu" />
  <TaskListDialog v-if="listDialog" :project="editingList" @close="listDialog=false" @saved="listSaved" />
  <ProjectSettingsModal v-if="settingsList" :project="settingsList" @close="settingsList=undefined" @deleted="settingsList=undefined;selection='all'" />
 </div>
</template>
<style scoped>
.reschedule-button { color:var(--wao-primary-text); padding:var(--wao-space-1); margin-left:auto; }
.task-workspace.navigation-collapsed {grid-template-columns:minmax(0,1fr);}
.navigation-toggle {display:inline-flex;align-items:center;justify-content:center;min-width:var(--wao-control-height);min-height:var(--wao-control-height);color:var(--wao-text-3);}

.list-heading,.list-entry { display:flex; align-items:center; gap:var(--wao-space-1); }
.list-heading { justify-content:space-between; margin-top:var(--wao-space-2); padding-inline:var(--wao-space-1); min-height:var(--wao-control-height); }
.task-navigation .list-heading p { margin:0; }
.list-heading>button { display:inline-flex; align-items:center; justify-content:center; width:var(--wao-control-height); height:var(--wao-control-height); padding:0; }
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
.bounded-workspace { flex:1; width:100%; min-height:0; overflow:hidden; }
.bounded-workspace .workspace-main { display:flex; flex-direction:column; min-height:0; overflow:hidden; }
.bounded-workspace .workspace-main>header,.bounded-workspace .quick-add { flex-shrink:0; }
.bounded-workspace .task-navigation { min-height:0; max-height:none; }
.bounded-workspace :deep(.checklist-layout) { flex:1; min-height:0; overflow:hidden; align-items:stretch; grid-template-rows:minmax(0,1fr); }
.bounded-workspace :deep(.checklist-tasks),.bounded-workspace :deep(.checklist-detail) { min-height:0; max-height:none; overflow:auto; overscroll-behavior:contain; }
.bounded-workspace :deep(.checklist-detail) { position:static; }
.bounded-workspace :deep(.checklist-divider) { min-height:0; }
.bounded-workspace :deep(.checklist-layout.is-compact) { display:flex; flex-direction:column; overflow:auto; }
.bounded-workspace :deep(.is-compact .checklist-list-pane) { flex:0 0 auto; min-height:320px; height:50dvh; }
.bounded-workspace :deep(.is-compact .checklist-detail) { flex:1 0 320px; }
@media(max-width:760px) { .bounded-workspace :deep(.checklist-layout) {display:flex;flex-direction:column;overflow:auto;} .bounded-workspace :deep(.checklist-list-pane) {flex:0 0 auto;min-height:320px;height:50dvh;} .bounded-workspace :deep(.checklist-detail) {flex:1 0 320px;} }
@media(max-width:1000px) { .task-workspace { grid-template-columns:160px minmax(0,1fr); }.workspace-main {padding:var(--wao-space-2);} }
@media(max-width:600px) { .task-workspace {display:block;} .task-navigation {max-height:200px; border-right:0;border-bottom:1px solid var(--wao-line);} header {flex-wrap:wrap;} .workspace-tools {margin-left:0;flex:1;} .workspace-tools input {width:140px;} .workspace-tools button {display:none;} .layout-switch {margin-left:auto;} .bounded-workspace {display:flex;flex-direction:column;} .bounded-workspace .task-navigation {max-height:120px;flex-shrink:0;} .bounded-workspace .workspace-main {flex:1;}button {min-height:44px;} }
.bounded-workspace :deep(.checklist-layout.popup-detail) { display:grid; overflow:hidden; }
.bounded-workspace :deep(.popup-detail .checklist-list-pane) { height:auto; min-height:0; }
</style>
