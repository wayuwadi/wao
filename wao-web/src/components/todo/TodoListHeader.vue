<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useInputComposition } from '@/composables/inputComposition'
import TaskCaptureFields from '@/components/task/TaskCaptureFields.vue'
import { emptyCapture, type CaptureFields } from '@/utils/taskCapture'
import TodayDates from '@/components/routines/TodayDates.vue'
import TodoViewControls from './TodoViewControls.vue'
import type { TodoViewSettings, TodoDisplaySettings } from '@/utils/todoViewSettings'
const props=withDefaults(defineProps<{ saved?:boolean; batchMode?:boolean; heading:string; count:number; selection:string; navigationCollapsed:boolean; creating:boolean; error:string; view:TodoViewSettings; display:TodoDisplaySettings; syncError:string; destination:string; preferencesReady?:boolean }>(),{preferencesReady:true})
const fields=defineModel<CaptureFields>('fields',{default:()=>emptyCapture()})
const query=defineModel<string>('query',{default:''})
const completed=defineModel<boolean>('completed',{default:false})
const quick=defineModel<string>('quick',{required:true})
const emit=defineEmits<{ batch:[]; navigation:[]; add:[]; templates:[]; protect:[]; view:[Partial<TodoViewSettings>]; display:[Partial<TodoDisplaySettings>]; retry:[] }>()
const composition=useInputComposition(),quickInput=ref<HTMLInputElement>(),quickForm=ref<HTMLFormElement>()
const searchInput=ref<HTMLInputElement>()
const searchComposition=useInputComposition()
function clearSearch(){query.value='';void nextTick(()=>searchInput.value?.focus({preventScroll:true}))}
function onSearchEscape(event: KeyboardEvent) {
  if (searchComposition.active(event)) { event.stopPropagation(); return }
  if (!query.value) return
  event.preventDefault()
  event.stopPropagation()
  clearSearch()
}
watch(()=>props.selection,()=>{ composition.end(); searchComposition.end() })
let restoreInput=false
function submit(){if(!composition.active()&&!props.creating&&quick.value.trim())emit('add')}
watch(()=>props.creating,async (creating,wasCreating)=>{
 if(creating){restoreInput=!!quickForm.value?.contains(document.activeElement);return}
 if(!wasCreating||!restoreInput)return
 restoreInput=false
 await nextTick()
 if(document.activeElement===document.body||quickForm.value?.contains(document.activeElement)) {
  const target=quickInput.value?.disabled ? quickForm.value?.querySelector<HTMLButtonElement>('button:not(:disabled)') : quickInput.value
  target?.focus({preventScroll:true})
 }
})
</script>
<template>
 <div class="todo-list-header">
  <header>
   <div class="heading-line">
    <button class="navigation-toggle" :aria-label="navigationCollapsed?'展开待办导航':'收起待办导航'" :aria-expanded="!navigationCollapsed" aria-controls="todo-navigation" @click="$emit('navigation')"><i class="ri-layout-left-line" aria-hidden="true" /></button>
    <h1>{{heading}}</h1><span class="count">{{count}}</span>
    <button v-if="!['completed','abandoned'].includes(selection)" class="completed-toggle" :aria-pressed="completed" @click="completed=!completed"><i class="ri-checkbox-circle-line" aria-hidden="true" />{{completed?'返回未完成':'已完成'}}</button>
    <button v-if="!['completed','abandoned'].includes(selection)" type="button" aria-label="使用任务模板" title="使用任务模板" @click="$emit('templates')"><i class="ri-file-copy-line" aria-hidden="true" /></button>
   </div>
   <div class="toolbar-line">
    <div class="task-search" role="search" aria-label="待办任务">
     <i class="ri-search-line" aria-hidden="true" />
     <input ref="searchInput" v-model="query" type="search" aria-label="搜索任务" placeholder="搜索任务" @compositionstart="searchComposition.start" @compositionend="searchComposition.end" @keydown.esc="onSearchEscape" />
     <button v-if="query" aria-label="清空搜索" title="清空搜索" @click="clearSearch" type="button"><i class="ri-close-line" aria-hidden="true" /></button>
    </div>
    <div v-if="$slots.filters" class="toolbar-filters"><slot name="filters" /></div>
    <button class="batch-toggle" type="button" aria-label="批量选择" :title="batchMode?'退出批量选择':'批量选择'" :aria-pressed="!!batchMode" :aria-controls="batchMode?'todo-batch-toolbar':undefined" @click="$emit('batch')"><i class="ri-checkbox-multiple-line" aria-hidden="true" /><span class="batch-label">批量选择</span></button>
    <div class="view-options"><TodoViewControls :ready="preferencesReady" :selection="selection" :view="view" :display="display" :sync-error="syncError" @view="$emit('view',$event)" @display="$emit('display',$event)" @retry="$emit('retry')" /></div>
   </div>
  </header>
  <TodayDates v-if="selection==='today'" />
  <form v-if="view.layout!=='quadrant' && !completed && !['completed','abandoned'].includes(selection)" ref="quickForm" class="quick-add" @submit.prevent="submit"><i class="ri-add-line" aria-hidden="true" /><input ref="quickInput" v-model="quick" aria-label="添加任务" :placeholder="`添加任务到「${destination}」`" :disabled="creating || saved" @input="$emit('protect')" @compositionstart="composition.start" @compositionend="composition.end" @keydown="composition.guard" /><button :disabled="creating||!quick.trim()">{{creating?'保存中…':saved?'重试标签':'添加'}}</button></form>
  <TaskCaptureFields v-if="view.layout!=='quadrant' && !completed && !['completed','abandoned'].includes(selection)" v-model="fields" :title="quick" :saved="saved" :disabled="creating" @title="quick=$event;$emit('protect')" />
  <p v-if="error" role="alert">{{error}}</p>
 </div>
</template>
<style scoped>
.completed-toggle { display:inline-flex; align-items:center; gap:4px; white-space:nowrap; }
.task-search {
 display:flex; align-items:center; gap:calc(var(--wao-space-1) / 2); flex:0 1 240px; min-width:0;
 min-height:var(--wao-control-height); margin-inline-end:auto; padding-inline:var(--wao-space-1);
 color:var(--wao-text-3); background:var(--wao-bg-sub); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control);
}
.task-search:focus-within { border-color:var(--wao-primary); outline:1px solid var(--wao-primary); }
.task-search input { min-height:0; padding-block:calc(var(--wao-space-1) / 2); appearance:none; }
.task-search input:focus-visible { outline:none; }
.task-search input::placeholder { color:var(--wao-text-3); }
.task-search input::-webkit-search-cancel-button { display:none; }
.task-search button { flex-shrink:0; padding:0; min-width:28px; min-height:28px; }

.todo-list-header { container-type:inline-size; flex-shrink:0; min-width:0; padding-bottom:var(--wao-space-2); font:400 var(--wao-font-body)/1.5 var(--wao-font); }
header { display:flex; align-items:center; gap:var(--wao-space-1); min-height:44px; margin-bottom:var(--wao-space-2); flex-wrap:wrap; }
.heading-line,.toolbar-line { display:flex; align-items:center; gap:var(--wao-space-1); min-width:0; }
.heading-line { flex:0 1 auto; }.toolbar-line { flex:1 1 380px; }
.toolbar-filters,.batch-toggle,.view-options { flex-shrink:0; }.toolbar-filters { min-width:0; }
.toolbar-filters :deep(.todo-organization) { margin:0; }.view-options :deep(.view-controls) { margin:0; }
.batch-toggle { display:inline-flex; align-items:center; gap:4px; white-space:nowrap; }
h1 { margin:0; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:var(--wao-font-title); font-weight:var(--wao-weight-heading); line-height:var(--wao-line-heading); }
.count { color:var(--wao-text-3); }
button { border:0; border-radius:var(--wao-radius-control); background:transparent; padding:var(--wao-space-1); color:var(--wao-text); font:inherit; cursor:pointer; }
button:hover { background:var(--wao-bg-hover); }button[aria-pressed=true] { background:var(--wao-primary-soft); color:var(--wao-primary-text); }button:disabled { opacity:.5; cursor:default; }
.quick-add { display:flex; align-items:center; gap:var(--wao-space-1); padding-inline:var(--wao-space-1); min-width:0; border-radius:var(--wao-radius-control); }
.quick-add { background:var(--wao-bg-sub); }
input { flex:1; width:0; min-width:0; padding:var(--wao-space-1) 0; min-height:24px; border:0; background:transparent; color:var(--wao-text); font:inherit; }
.quick-add button { color:var(--wao-primary-text); }.navigation-toggle { flex-shrink:0; }
button:focus-visible,input:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
p { margin:var(--wao-space-1) 0 0; color:var(--wao-danger-strong); }
@container(max-width:520px) { .heading-line,.toolbar-line { flex-basis:100%; }.heading-line h1 { flex:1; }.toolbar-line { gap:4px; flex-wrap:wrap; }.batch-label { display:none; }.batch-toggle { justify-content:center; min-width:36px; }.task-search { flex:1 1 100px; }.heading-line>button { flex-shrink:0; } }
@container(max-width:340px) { .task-search { flex-basis:100%; } }
@media(pointer:coarse) { button,.task-search button { min-height:44px; } .task-search { min-height:46px; } .task-search button { min-width:44px; } }
</style>
