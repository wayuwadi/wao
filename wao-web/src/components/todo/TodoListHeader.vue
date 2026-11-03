<script setup lang="ts">
import TodayDates from '@/components/routines/TodayDates.vue'
defineProps<{ heading:string; count:number; selection:string; navigationCollapsed:boolean; creating:boolean; error:string }>()
const query=defineModel<string>('query',{required:true}), quick=defineModel<string>('quick',{required:true})
const layout=defineModel<'list'|'quadrant'>('layout',{required:true})
defineEmits<{ navigation:[]; add:[]; protect:[] }>()
</script>
<template>
 <div class="todo-list-header">
  <header><button class="navigation-toggle" :aria-label="navigationCollapsed?'展开待办导航':'收起待办导航'" :aria-expanded="!navigationCollapsed" aria-controls="todo-navigation" @click="$emit('navigation')"><i class="ri-layout-left-line" /></button><h1>{{heading}}</h1><span class="count">{{count}}</span><div class="layout-switch"><button :aria-pressed="layout==='list'" @click="layout='list'">清单</button><button :aria-pressed="layout==='quadrant'" @click="layout='quadrant'">四象限</button></div>
  <label class="workspace-search"><i class="ri-search-line" aria-hidden="true" /><input v-model="query" aria-label="搜索任务" placeholder="搜索任务及子任务" /></label></header>
  <TodayDates v-if="selection==='today'" />
  <form v-if="layout==='list' && selection!=='completed'" class="quick-add" @submit.prevent="$emit('add')"><i class="ri-add-line" aria-hidden="true" /><input v-model="quick" aria-label="添加任务" :placeholder="selection.startsWith('project:')?'添加任务到当前清单':'添加任务到收集箱'" :disabled="creating" @input="$emit('protect')" /><button :disabled="creating||!quick.trim()">{{creating?'保存中…':'添加'}}</button></form>
  <p v-if="error" role="alert">{{error}}</p>
 </div>
</template>
<style scoped>
.todo-list-header { flex-shrink:0; min-width:0; padding-bottom:var(--wao-space-2); font:400 var(--wao-font-body)/1.5 var(--wao-font); }
header { display:flex; align-items:center; gap:var(--wao-space-1); min-height:44px; margin-bottom:var(--wao-space-2); flex-wrap:wrap; }
h1 { margin:0; min-width:0; overflow-wrap:anywhere; font-size:var(--wao-font-title); font-weight:var(--wao-weight-heading); line-height:var(--wao-line-heading); }
.count { color:var(--wao-text-3); }.layout-switch { display:flex; flex-shrink:0; margin-left:var(--wao-space-1); gap:var(--wao-space-1); }
button { border:0; border-radius:var(--wao-radius-control); background:transparent; padding:var(--wao-space-1); color:var(--wao-text); font:inherit; cursor:pointer; }
button:hover { background:var(--wao-bg-hover); }button[aria-pressed=true] { background:var(--wao-primary-soft); color:var(--wao-primary-text); }button:disabled { opacity:.5; cursor:default; }
.workspace-search,.quick-add { display:flex; align-items:center; gap:var(--wao-space-1); padding-inline:var(--wao-space-1); min-width:0; border-radius:var(--wao-radius-control); }
.workspace-search { color:var(--wao-text-3); flex:1 1 160px; max-width:320px; margin-left:var(--wao-space-1); }.quick-add { background:var(--wao-bg-sub); }
input { flex:1; width:0; min-width:0; padding:var(--wao-space-1) 0; min-height:24px; border:0; background:transparent; color:var(--wao-text); font:inherit; }
.quick-add button { color:var(--wao-primary-text); }.navigation-toggle { flex-shrink:0; }
button:focus-visible,input:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
p { margin:var(--wao-space-1) 0 0; color:var(--wao-danger-strong); }
@media(pointer:coarse) { button { min-height:44px; } }
</style>
