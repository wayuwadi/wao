<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useTaskLabelsStore } from '@/stores/taskLabels'
import { useSessionStore } from '@/stores/session'
import WaoButton from '@/components/ui/WaoButton.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import type { Task, Priority } from '@/types'
import type { ProjectItemRefs } from '@/utils/projectWorkItems'
import { deadlineFeedback, type DeadlineChange } from '@/composables/taskChangeFeedback'
import { toast } from '@/composables/toast'
const props = defineProps<{ selectedIds: string[]; items: Task[]; itemRefs: ProjectItemRefs }>()
const emit = defineEmits<{ 'update:selectedIds': [ids: string[]] }>()
const data = useDataStore(), labels = useTaskLabelsStore(), session = useSessionStore()
const mode = ref<'' | 'tags' | 'priority' | 'complete' | 'move' | 'date'>('')
const tagText = ref(''), removeTags = ref(false), priority = ref<Priority>('P2'), project = ref('')
const due=ref('')
const snapshot=ref<{id:string;source:{taskId:string;subId?:string};title:string}[]>([])
const saving = ref(false), errors = ref<string[]>([])
let alive = true, identityEpoch = 0
const attempted = new Set<string>()
const originalDates=new Map<string,DeadlineChange>()
watch(()=>labels.identity,()=>{identityEpoch++;due.value='';tagText.value='';removeTags.value=false;priority.value='P2';project.value='';mode.value='';snapshot.value=[];errors.value=[];saving.value=false;attempted.clear();originalDates.clear()},{flush:'sync'})
onBeforeUnmount(() => { alive = false; identityEpoch++ })
const movable = computed(() => props.selectedIds.every(id => {
  const ref = props.itemRefs[id], task = data.taskById(ref?.taskId ?? id)
  return task && !ref?.subId && !task.parentTaskId && !data.tasks.some(child => child.parentTaskId === task.id)
}))
function open(value: typeof mode.value) { if(saving.value)return; mode.value = value; snapshot.value=[...new Set(props.selectedIds)].map(id=>({id,source:{...(props.itemRefs[id] ?? {taskId:id})},title:props.items.find(t=>t.id===id)?.title ?? id})); errors.value = []; attempted.clear(); originalDates.clear() }
async function apply() {
  if (saving.value || !mode.value || !snapshot.value.length) return
  const identity = labels.identity, epoch=identityEpoch, operation = mode.value
  const current=()=>alive && identity===labels.identity && epoch===identityEpoch
  const selected = [...snapshot.value]
  const dueValue=due.value, priorityValue=priority.value, projectValue=project.value, removing=removeTags.value
  const tags = [...new Set(tagText.value.split(/[,，]/).map(t => t.trim()).filter(Boolean))]
  if (operation === 'tags' && !tags.length) { errors.value = ['请输入至少一个标签']; return }
  if (operation === 'move' && !selected.every(item=>{const task=data.taskById(item.source.taskId);return task && !item.source.subId && !task.parentTaskId && !data.tasks.some(child=>child.parentTaskId===task.id)})) { errors.value = ['有关联父子关系的任务请先解除关系；内嵌子项随主任务移动']; return }
  saving.value = true; errors.value = []
  const succeeded = new Set<string>()
  const dateChanges:DeadlineChange[]=[]
  for (const item of selected) {
    if (!current()) break
    try {
      const { taskId, subId } = item.source
      const task = data.taskById(taskId)
      if (!task || (subId && !data.subById(taskId, subId))) throw new Error('任务已不存在')
      if (operation === 'tags') {
        if (!labels.ready) throw new Error('标签尚未读取成功，请稍后重试')
        const current = labels.get(taskId, subId)
        await labels.save({ ...current, tags: removing ? current.tags.filter(tag => !tags.includes(tag)) : [...new Set([...current.tags, ...tags])] })
      } else {
        if (data.taskSaveState(taskId)==='saving' || (data.taskSaveState(taskId)==='error' && attempted.has(taskId))) await data.flushTask(taskId)
        if (!current()) break
        if (data.taskSaveState(taskId) === 'error') throw new Error('已有未保存或冲突的修改，请先在详情中处理')
        attempted.add(taskId)
        if (operation === 'priority') {
          if (subId) data.updateSub(taskId, subId, { pr: priorityValue })
          else data.patchTask(taskId, { pr: priorityValue })
        } else if (operation === 'date') {
          if(!originalDates.has(item.id)) originalDates.set(item.id,{taskId,subId,before:subId?data.subById(taskId,subId)?.due:task.due,after:dueValue})
          dateChanges.push({...originalDates.get(item.id)!,after:dueValue})
          if(subId) data.updateSub(taskId,subId,{due:dueValue})
          else data.patchTask(taskId,{due:dueValue})
        } else if (operation === 'complete') {
          if (subId) data.setSubState(taskId, subId, '已完成')
          else data.patchTask(taskId, { status: '已完成' })
        } else if (operation === 'move') {
          if (!projectValue && task.owner !== session.userId) throw new Error('仅能把自己负责的任务移入个人收集箱')
          data.patchTask(taskId, { project: projectValue, groupId: '' })
        }
        await data.flushTask(taskId)
        if (!current()) return
        if (data.taskSaveState(taskId) === 'error') throw new Error(data.taskSaveError(taskId) || '保存失败，修改已保留')
      }
      if (!current()) return
      succeeded.add(item.id)
    } catch (error) { if (current()) errors.value.push(`${item.title}：${error instanceof Error ? error.message : '未保存，请重试'}`) }
  }
  if (!current()) return
  saving.value = false
  emit('update:selectedIds', props.selectedIds.filter(id => !succeeded.has(id)))
  snapshot.value=snapshot.value.filter(item=>!succeeded.has(item.id))
  if(operation==='date')deadlineFeedback(dateChanges.filter(change=>selected.some(item=>succeeded.has(item.id)&&item.source.taskId===change.taskId&&item.source.subId===change.subId)))
  if (succeeded.size && operation!=='date') toast(`已更新 ${succeeded.size} 项任务，筛选和四象限已同步`, 'ri-check-line')
  if (!errors.value.length) mode.value = ''
}
</script>
<template>
  <div class="batch-actions" aria-label="批量任务操作">
    <WaoButton :disabled="!selectedIds.length" @click="open('tags')">设置标签</WaoButton>
    <WaoButton :disabled="!selectedIds.length" @click="open('date')">修改日期</WaoButton>
    <WaoButton :disabled="!selectedIds.length" @click="open('priority')">优先级</WaoButton>
    <WaoButton :disabled="!selectedIds.length" @click="open('complete')">完成任务</WaoButton>
    <WaoButton :disabled="!selectedIds.length || !movable" title="内嵌子项随主任务移动；有关联父子关系的任务需先解除关系" @click="open('move')">移动清单</WaoButton>
    <SettingsDialogShell v-if="mode" :title="`批量${{date:'修改截止日期',tags:'设置标签',priority:'修改优先级',complete:'完成任务',move:'移动清单'}[mode]}`" width="480px" height="auto" @close="!saving && (mode='')">
      <form @submit.prevent="apply">
        <p v-if="mode!=='move'">将处理 {{ snapshot.length }} 项选中的任务；未选中的子任务保持原状。</p>
        <label v-if="mode==='date'">截止日期<input v-model="due" aria-label="批量截止日期" type="date" :disabled="saving" /><small>留空清除截止日期，不改变执行安排。</small></label>
        <template v-if="mode==='tags'"><label>标签<input v-model="tagText" :disabled="saving" aria-label="批量标签" placeholder="用逗号分隔多个标签" maxlength="820" /></label><label><input v-model="removeTags" :disabled="saving" type="checkbox" />移除这些标签（默认追加）</label></template>
        <label v-if="mode==='priority'">优先级<select v-model="priority" :disabled="saving"><option value="P0">P0 · 重要紧急</option><option value="P1">P1 · 重要不紧急</option><option value="P2">P2 · 不重要紧急</option><option value="P3">P3 · 不重要不紧急</option></select></label>
        <label v-if="mode==='move'">目标清单<select v-model="project" :disabled="saving"><option value="">收集箱</option><option v-for="p in data.projects" :key="p.id" :value="p.id">{{ p.name }}</option></select><small>内嵌子项会随主任务一起移动。</small></label>
        <ul v-if="errors.length" role="alert"><li v-for="error in errors" :key="error">{{ error }}</li></ul>
        <footer><WaoButton type="button" :disabled="saving" @click="mode=''">取消</WaoButton><WaoButton type="submit" :disabled="saving || !selectedIds.length">{{ saving?'保存中…':errors.length?'重试剩余任务':'确认' }}</WaoButton></footer>
      </form>
    </SettingsDialogShell>
  </div>
</template>
<style scoped>
.batch-actions,footer { display:flex; align-items:center; gap:var(--wao-space-1); flex-wrap:wrap; }
form { padding:var(--wao-space-2); display:flex; flex-direction:column; gap:var(--wao-space-2); color:var(--wao-text); font:400 var(--wao-font-body)/1.5 var(--wao-font); }
p { margin:0; } label { display:flex; align-items:center; gap:var(--wao-space-1); flex-wrap:wrap; }
input:not([type=checkbox]),select { flex:1; min-width:0; padding:var(--wao-space-1); font:inherit; color:inherit; background:var(--wao-bg-card); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); }
ul { color:var(--wao-danger-strong); max-height:200px; overflow:auto; } small { color:var(--wao-text-3); } footer { justify-content:flex-end; }
@media(pointer:coarse) { input,select { min-height:44px; } }
</style>
