<script setup lang="ts">
import { computed,ref,watch,onMounted,onBeforeUnmount } from 'vue'
import { useRoutinesStore } from '@/stores/routines'
import { newRoutine,type RoutineItem } from '@/api/personalRoutines'
import RoutineEditor from '@/components/routines/RoutineEditor.vue'
import DateCounterCard from '@/components/routines/DateCounterCard.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
const store=useRoutinesStore(),status=ref('active'),category=ref(''),search=ref(''),editing=ref(false)
const items=computed(()=>(store.state?.items||[]).filter(i=>i.kind==='countdown'&&i.status===status.value&&(!category.value||i.category===category.value)&&i.name.toLowerCase().includes(search.value.toLowerCase())).sort((a,b)=>Number(b.pinned)-Number(a.pinned)||(Math.abs(store.summary(a.id)?.days??Infinity)-Math.abs(store.summary(b.id)?.days??Infinity))))
function edit(item?:RoutineItem){if(!store.drafts.countdown)store.edit(item??newRoutine('countdown',store.state!.today));editing.value=true}
async function action(item:RoutineItem,change:Partial<RoutineItem>){await store.save({...item,...change})}
watch(()=>store.identity,()=>{editing.value=false;void store.load()},{immediate:true})
let timer:ReturnType<typeof setInterval>;function refresh(){void store.load()}
onMounted(()=>{timer=setInterval(refresh,60_000);window.addEventListener('focus',refresh)});onBeforeUnmount(()=>{clearInterval(timer);window.removeEventListener('focus',refresh)})
</script>
<template><div class="routine-page">
 <header><div><h1>倒数日</h1><p>记住值得期待，也值得纪念的日子。</p></div><WaoButton variant="primary" :disabled="!store.state" @click="edit()">添加日期</WaoButton></header>
 <p v-if="store.error" class="error" role="alert">{{store.error}} <WaoButton @click="store.load()">重试</WaoButton></p><p v-if="store.draftError" class="error" role="alert">{{store.draftError}}</p>
 <div v-if="!editing&&store.drafts.countdown?.revision" class="draft-banner">有未保存的日期草稿：{{store.drafts.countdown.name||'未命名'}} <WaoButton @click="editing=true">继续编辑</WaoButton><WaoButton @click="store.clear('countdown')">舍弃草稿</WaoButton></div>
 <div class="routine-toolbar"><input v-model="search" aria-label="搜索倒数日" placeholder="搜索日期名称" /><select v-model="category" aria-label="日期类型筛选"><option value="">全部类型</option><option value="countdown">倒数日</option><option value="anniversary">纪念日</option><option value="birthday">生日</option><option value="holiday">节日</option></select><select v-model="status" aria-label="日期状态筛选"><option value="active">进行中</option><option value="archived">已归档</option><option value="trash">回收站</option></select></div>
 <p v-if="!store.state" role="status">{{store.loading?'正在读取日期…':'日期尚未读取'}}</p><p v-else-if="!items.length" class="empty">{{status==='active'?'还没有匹配的日期，添加一个值得记住的日子。':'这里还没有记录。'}}</p>
 <div class="date-grid"><DateCounterCard v-for="item in items" :key="item.id" :item="item" :summary="store.summary(item.id)" @edit="edit(item)"><template #actions><WaoButton variant="ghost" :aria-label="`${item.pinned?'取消置顶':'置顶'} ${item.name}`" :disabled="store.busy" @click="action(item,{pinned:!item.pinned})"><i :class="item.pinned?'ri-pushpin-fill':'ri-pushpin-line'"></i></WaoButton><details><summary :aria-label="`${item.name} 更多操作`">•••</summary><div class="routine-menu"><button @click="edit(item)">编辑、样式与备注</button><button :disabled="store.busy" @click="action(item,{status:item.status==='active'?'archived':'active'})">{{item.status==='active'?'归档':'恢复到进行中'}}</button><button v-if="item.status!=='trash'" :disabled="store.busy" @click="action(item,{status:'trash'})">移入回收站</button></div></details></template></DateCounterCard></div>
 <RoutineEditor v-if="editing&&store.drafts.countdown" kind="countdown" @close="editing=false" />
</div></template>
<style scoped>
@import '@/styles/routines.css';
.date-grid {display:grid;grid-template-columns:repeat(auto-fill,minmax(min(100%,280px),1fr));gap:var(--wao-space-3);align-items:start;}
</style>
