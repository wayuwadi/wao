import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useSessionStore } from './session'
import { fetchRoutines, saveRoutine, saveHabitLog, type RoutineItem, type RoutineKind, type RoutineState, type HabitLog } from '@/api/personalRoutines'
export const useRoutinesStore=defineStore('routines',()=>{
 const session=useSessionStore(), identity=computed(()=>session.account?`${session.account.accountId}:${session.userId}`:'')
 const state=ref<RoutineState|null>(null),error=ref(''),draftError=ref(''),busy=ref(false),loading=ref(false)
 const drafts=ref<Partial<Record<RoutineKind,RoutineItem>>>({}), logDraft=ref<HabitLog|null>(null)
 let generation=0,sequence=0,restoring=false
 const key=()=>`wao-routines-drafts:${identity.value}`
 function protect(){if(!identity.value||restoring)return;try{localStorage.setItem(key(),JSON.stringify({drafts:drafts.value,log:logDraft.value}));draftError.value=''}catch{draftError.value='本机草稿无法保存，请保持页面并备份输入内容'}}
 watch([drafts,logDraft],protect,{deep:true,flush:'sync'})
 async function load(month=state.value?.month){
  if(!identity.value||busy.value)return
  const epoch=generation,request=++sequence;loading.value=true
  try{const value=await fetchRoutines(month);if(epoch===generation&&request===sequence){state.value=value;error.value=''}}
  catch(e:any){if(epoch===generation)error.value=e.message||'读取失败，请重试'}
  finally{if(epoch===generation&&request===sequence)loading.value=false}
 }
 function edit(item:RoutineItem){drafts.value[item.kind]=JSON.parse(JSON.stringify(item))}
 function clear(kind:RoutineKind){delete drafts.value[kind]}
 async function save(item:RoutineItem,discardDraft=false){
  if(busy.value)return false
  const epoch=generation;busy.value=true;error.value='';sequence++
  try {await saveRoutine(JSON.parse(JSON.stringify(item)));if(epoch!==generation)return false;if(discardDraft)clear(item.kind);return true}
  catch(e:any){if(epoch===generation)error.value=e.message||'保存失败，草稿已保留';return false}
  finally{if(epoch===generation){busy.value=false;const message=error.value;await load();if(message)error.value=message}}
 }
 async function saveLog(){
  if(busy.value||!logDraft.value)return false
  const epoch=generation,submitted=JSON.parse(JSON.stringify(logDraft.value));busy.value=true;error.value='';sequence++
  try{await saveHabitLog(submitted);if(epoch!==generation)return false;logDraft.value=null;return true}
  catch(e:any){if(epoch===generation)error.value=e.message||'打卡未保存，日志已保留';return false}
  finally{if(epoch===generation){busy.value=false;const message=error.value;await load();if(message)error.value=message}}
 }
 const item=(id:string)=>state.value?.items.find(i=>i.id===id)
 const summary=(id:string)=>state.value?.summaries.find(i=>i.id===id)
 const log=(id:string,day:string)=>state.value?.logs.find(l=>l.habitId===id&&l.date===day)
 watch(identity,()=>{
  generation++;sequence++;state.value=null;error.value='';draftError.value='';busy.value=false;loading.value=false
  // Read before replacing refs: the deep watcher must not erase another member's stored draft.
  let stored:any=null;try{stored=identity.value?JSON.parse(localStorage.getItem(key())||'null'):null}catch{draftError.value='草稿读取失败，请勿清除浏览器数据'}
  restoring=true;drafts.value=stored?.drafts??{};logDraft.value=stored?.log??null;restoring=false
 },{immediate:true,flush:'sync'})
 return {identity,state,error,draftError,busy,loading,drafts,logDraft,load,edit,clear,save,saveLog,item,summary,log}
})
