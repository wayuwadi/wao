import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useDataStore } from './data'
import { subtaskRows } from '@/utils/subtaskTree'
import { useSessionStore } from './session'
import { fetchTaskLabels, saveTaskLabels, type TaskLabels } from '@/api/taskLabels'
export const taskLabelKey = (taskId: string, subId = '') => JSON.stringify([taskId, subId])
export const useTaskLabelsStore = defineStore('task-labels', () => {
 const session = useSessionStore()
 const rows = ref<Record<string, TaskLabels>>({}), error = ref(''), loading = ref(false), ready = ref(false)
 let epoch = 0, sequence = 0
 const identity = computed(() => `${session.account?.accountId ?? ''}:${session.userId}`)
 async function load() {
  const generation = epoch, request = ++sequence
  loading.value = true
  try {
   const result = await fetchTaskLabels()
   if (generation !== epoch || request !== sequence) return
   rows.value = Object.fromEntries(result.map(row => [taskLabelKey(row.taskId, row.subId), row]))
   error.value = ''; ready.value = true
  } catch { if (generation === epoch && request === sequence) error.value = '标签未能读取，请重试' }
  finally { if (generation === epoch && request === sequence) loading.value = false }
 }
 watch(identity, () => { epoch++; sequence++; rows.value = {}; ready.value = false; error.value = ''; loading.value = false }, { flush: 'sync' })
 const get = (taskId: string, subId = ''): TaskLabels => rows.value[taskLabelKey(taskId, subId)] ?? { taskId, subId, revision: 0, tags: [] }
 async function save(value: TaskLabels) {
  const generation = epoch
  const result = await saveTaskLabels(value)
  if (generation !== epoch) throw new Error('账号已切换')
  sequence++; loading.value = false; ready.value = true
  rows.value[taskLabelKey(result.taskId, result.subId)] = result
  return result
 }
 const catalog = computed(() => { const valid=new Set(useDataStore().tasks.flatMap(task=>[taskLabelKey(task.id),...subtaskRows(task.id,task.subs).map(row=>taskLabelKey(task.id,row.key))])); return [...new Set(Object.entries(rows.value).filter(([key])=>valid.has(key)).flatMap(([,row])=>row.tags))].sort((a,b)=>a.localeCompare(b,'zh-CN')) })
 return { rows, error, loading, ready, catalog, identity, get, load, save }
})
