<script setup lang="ts">
import { computed,watch,onMounted,onBeforeUnmount } from 'vue'
import { useRoutinesStore } from '@/stores/routines'
const store=useRoutinesStore()
const dates=computed(()=>store.state?.items.filter(i=>i.kind==='countdown'&&i.status==='active'&&i.showToday&&store.summary(i.id)?.occurrence===store.state?.today)||[])
watch(()=>store.identity,()=>void store.load(),{immediate:true})
let timer:ReturnType<typeof setInterval>;onMounted(()=>{timer=setInterval(()=>void store.load(),60_000)});onBeforeUnmount(()=>clearInterval(timer))
</script>
<template><aside v-if="dates.length||store.error" class="today-dates" aria-label="今日纪念日"><p v-if="store.error" role="alert">日期提醒暂未读取 <button @click="store.load()">重试</button></p><RouterLink v-for="date in dates" :key="date.id" to="/countdowns">{{date.emoji}} 今天 · {{date.name}}</RouterLink></aside></template>
<style scoped>
.today-dates{display:flex;gap:var(--wao-space-2);flex-wrap:wrap;padding:var(--wao-space-2);margin-bottom:var(--wao-space-2);background:var(--wao-primary-tint);border:1px solid var(--wao-primary-line);border-radius:var(--wao-radius-control);}.today-dates a{color:var(--wao-primary-text);text-decoration:none;overflow-wrap:anywhere;}.today-dates button{color:var(--wao-primary-text);font:inherit;background:transparent;border:0;cursor:pointer;}.today-dates p{margin:0;color:var(--wao-text-3);}
</style>
