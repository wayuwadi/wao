<script setup lang="ts">
import { computed, ref } from 'vue'
const props=defineProps<{modelValue:string[]; options:{value:string;label:string}[]; label:string}>()
const emit=defineEmits<{'update:modelValue':[value:string[]]}>()
const open=ref(false)
const caption=computed(()=>props.modelValue.length?props.modelValue.map(v=>props.options.find(o=>o.value===v)?.label??v).join('、'):'所有')
function toggle(value:string) { emit('update:modelValue',props.modelValue.includes(value)?props.modelValue.filter(v=>v!==value):[...props.modelValue,value]) }
</script>
<template><div class="filter-select" @keydown.esc.stop.prevent="open=false"><button type="button" :aria-label="label" :aria-expanded="open" @click="open=!open">{{ caption }}<i class="ri-arrow-down-s-line"></i></button><div v-if="open" class="filter-options" :aria-label="label"><button type="button" @click="emit('update:modelValue',[])">所有</button><label v-for="option in options" :key="option.value"><input type="checkbox" :checked="modelValue.includes(option.value)" @change="toggle(option.value)" />{{ option.label }}</label><small v-if="!options.length">暂无标签，可在任务详情中添加</small><button type="button" @click="open=false">确定</button></div></div></template>
<style scoped>
.filter-select { min-width:0; position:relative; }
.filter-select>button { width:100%; display:flex; align-items:center; justify-content:space-between; text-align:left; }
button { padding:var(--wao-space-1); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); background:var(--wao-bg-card); color:var(--wao-text); font:inherit; cursor:pointer; }
.filter-options { position:absolute; inset-inline:0; top:100%; z-index:10; background:var(--wao-bg-card); border:1px solid var(--wao-line); border-radius:var(--wao-radius-control); box-shadow:var(--wao-shadow-pop); max-height:200px; overflow:auto; padding:var(--wao-space-1); }
.filter-options label { display:flex; align-items:center; gap:var(--wao-space-1); padding:var(--wao-space-1); }
.filter-options button { border:0; color:var(--wao-primary-text); }
button:focus-visible { outline:2px solid var(--wao-primary); }
</style>
