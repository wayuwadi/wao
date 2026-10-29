<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
const props=defineProps<{modelValue:string[]; options:{value:string;label:string}[]; label:string}>()
const emit=defineEmits<{'update:modelValue':[value:string[]]}>()
const open=ref(false)
const root=ref<HTMLElement|null>(null), trigger=ref<HTMLButtonElement|null>(null)
function close(restore=false) { open.value=false; if(restore) void nextTick(()=>trigger.value?.focus()) }
function escape(event:KeyboardEvent) { if(!open.value) return; event.preventDefault(); event.stopPropagation(); close(true) }
function outside(event:PointerEvent) { if(event.target instanceof Node && !root.value?.contains(event.target)) close() }
onMounted(()=>document.addEventListener('pointerdown',outside))
onBeforeUnmount(()=>document.removeEventListener('pointerdown',outside))
const caption=computed(()=>props.modelValue.length?props.modelValue.map(v=>props.options.find(o=>o.value===v)?.label??v).join('、'):'所有')
function toggle(value:string) { emit('update:modelValue',props.modelValue.includes(value)?props.modelValue.filter(v=>v!==value):[...props.modelValue,value]) }
</script>
<template><div ref="root" class="filter-select" @keydown.esc="escape"><button ref="trigger" type="button" :aria-label="label" :aria-expanded="open" @click="open=!open"><span>{{ caption }}</span><i class="ri-arrow-down-s-line"></i></button><div v-if="open" class="filter-options" :aria-label="label"><button type="button" :aria-pressed="!modelValue.length" @click="emit('update:modelValue',[])">所有</button><label v-for="option in options" :key="option.value" :class="{selected:modelValue.includes(option.value)}"><input type="checkbox" :checked="modelValue.includes(option.value)" @change="toggle(option.value)" /><span>{{ option.label }}</span></label><small v-if="!options.length">暂无可选项</small><button type="button" @click="close(true)">确定</button></div></div></template>
<style scoped>
.filter-select { min-width:0; position:relative; font-family:var(--wao-font); font-size:var(--wao-font-body); line-height:1.5; font-weight:400; }
.filter-select>button>span { overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }
.filter-select>button { width:100%; display:flex; align-items:center; justify-content:space-between; text-align:left; }
button { padding:var(--wao-space-1); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); background:var(--wao-bg-card); color:var(--wao-text); font:inherit; cursor:pointer; }
.filter-options { position:absolute; inset-inline:0; top:100%; z-index:10; background:var(--wao-bg-card); border:1px solid var(--wao-line); border-radius:var(--wao-radius-control); box-shadow:var(--wao-shadow-pop); max-height:200px; overflow:auto; padding:var(--wao-space-1); }
.filter-options label { display:flex; align-items:center; gap:var(--wao-space-1); padding:6px var(--wao-space-1); min-height:34px; border-radius:var(--wao-radius-control); cursor:pointer; }
.filter-options label>span { min-width:0; overflow-wrap:anywhere; }
.filter-options label:hover { background:var(--wao-bg-hover); }
.filter-options label.selected,.filter-options button[aria-pressed=true] { background:var(--wao-primary-soft); color:var(--wao-primary-text); }
.filter-options input { accent-color:var(--wao-primary); flex-shrink:0; }
.filter-options button { border:0; color:var(--wao-primary-text); }
button:focus-visible { outline:2px solid var(--wao-primary); }
@media(pointer:coarse) { .filter-options label,.filter-options button { min-height:44px; } }
</style>
