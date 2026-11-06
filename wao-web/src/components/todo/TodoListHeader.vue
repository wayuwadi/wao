<script setup lang="ts">
import TodayDates from '@/components/routines/TodayDates.vue'
import TodoViewControls from './TodoViewControls.vue'
import type { TodoViewSettings, TodoDisplaySettings } from '@/utils/todoViewSettings'
withDefaults(defineProps<{ heading:string; count:number; selection:string; navigationCollapsed:boolean; creating:boolean; error:string; view:TodoViewSettings; display:TodoDisplaySettings; syncError:string; destination:string; preferencesReady?:boolean }>(),{preferencesReady:true})
const query=defineModel<string>('query',{default:''})
const completed=defineModel<boolean>('completed',{default:false})
const quick=defineModel<string>('quick',{required:true})
defineEmits<{ navigation:[]; add:[]; templates:[]; protect:[]; view:[Partial<TodoViewSettings>]; display:[Partial<TodoDisplaySettings>]; retry:[] }>()
</script>
<template>
 <div class="todo-list-header">
  <header><button class="navigation-toggle" :aria-label="navigationCollapsed?'展开待办导航':'收起待办导航'" :aria-expanded="!navigationCollapsed" aria-controls="todo-navigation" @click="$emit('navigation')"><i class="ri-layout-left-line" /></button><h1>{{heading}}</h1><span class="count">{{count}}</span>
   <button v-if="!['completed','abandoned'].includes(selection)" class="completed-toggle" :aria-pressed="completed" @click="completed=!completed"><i class="ri-checkbox-circle-line" aria-hidden="true" />{{completed?'返回未完成':'已完成'}}</button>
   <label class="task-search"><i class="ri-search-line" aria-hidden="true" /><input v-model="query" type="search" aria-label="搜索任务" placeholder="搜索任务（含已完成）" /><button v-if="query" aria-label="清空搜索" @click="query=''" type="button"><i class="ri-close-line" aria-hidden="true" /></button></label>
   <button v-if="!['completed','abandoned'].includes(selection)" type="button" aria-label="使用任务模板" title="使用任务模板" @click="$emit('templates')"><i class="ri-file-copy-line" aria-hidden="true" /></button>
   <TodoViewControls :ready="preferencesReady" :selection="selection" :view="view" :display="display" :sync-error="syncError" @view="$emit('view',$event)" @display="$emit('display',$event)" @retry="$emit('retry')" />
</header>
  <TodayDates v-if="selection==='today'" />
  <form v-if="view.layout!=='quadrant' && !completed && !['completed','abandoned'].includes(selection)" class="quick-add" @submit.prevent="$emit('add')"><i class="ri-add-line" aria-hidden="true" /><input v-model="quick" aria-label="添加任务" :placeholder="`添加任务到「${destination}」`" :disabled="creating" @input="$emit('protect')" /><button :disabled="creating||!quick.trim()">{{creating?'保存中…':'添加'}}</button></form>
  <p v-if="error" role="alert">{{error}}</p>
 </div>
</template>
<style scoped>
.completed-toggle { display:inline-flex; align-items:center; gap:4px; white-space:nowrap; }
.task-search { display:flex; align-items:center; gap:var(--wao-space-1); flex:1 1 180px; min-width:140px; color:var(--wao-text-3); }
.task-search input { width:100%; }

.todo-list-header { flex-shrink:0; min-width:0; padding-bottom:var(--wao-space-2); font:400 var(--wao-font-body)/1.5 var(--wao-font); }
header { display:flex; align-items:center; gap:var(--wao-space-1); min-height:44px; margin-bottom:var(--wao-space-2); flex-wrap:wrap; }
h1 { margin:0; min-width:0; overflow-wrap:anywhere; font-size:var(--wao-font-title); font-weight:var(--wao-weight-heading); line-height:var(--wao-line-heading); }
.count { color:var(--wao-text-3); }
button { border:0; border-radius:var(--wao-radius-control); background:transparent; padding:var(--wao-space-1); color:var(--wao-text); font:inherit; cursor:pointer; }
button:hover { background:var(--wao-bg-hover); }button[aria-pressed=true] { background:var(--wao-primary-soft); color:var(--wao-primary-text); }button:disabled { opacity:.5; cursor:default; }
.quick-add { display:flex; align-items:center; gap:var(--wao-space-1); padding-inline:var(--wao-space-1); min-width:0; border-radius:var(--wao-radius-control); }
.quick-add { background:var(--wao-bg-sub); }
input { flex:1; width:0; min-width:0; padding:var(--wao-space-1) 0; min-height:24px; border:0; background:transparent; color:var(--wao-text); font:inherit; }
.quick-add button { color:var(--wao-primary-text); }.navigation-toggle { flex-shrink:0; }
button:focus-visible,input:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
p { margin:var(--wao-space-1) 0 0; color:var(--wao-danger-strong); }
@media(pointer:coarse) { button { min-height:44px; } }
</style>
