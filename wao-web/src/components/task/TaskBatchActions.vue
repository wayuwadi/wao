<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { useTaskLabelsStore } from '@/stores/taskLabels'
import { useSessionStore } from '@/stores/session'
import WaoButton from '@/components/ui/WaoButton.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import type { Task, Priority } from '@/types'
import type { ProjectItemRefs } from '@/utils/projectWorkItems'
import { toast } from '@/composables/toast'
const props = defineProps<{ selectedIds: string[]; items: Task[]; itemRefs: ProjectItemRefs }>()
const emit = defineEmits<{ 'update:selectedIds': [ids: string[]] }>()
const data = useDataStore(), labels = useTaskLabelsStore(), session = useSessionStore()
const mode = ref<'' | 'tags' | 'priority' | 'complete' | 'move'>('')
const tagText = ref(''), removeTags = ref(false), priority = ref<Priority>('P2'), project = ref('')
const saving = ref(false), errors = ref<string[]>([])
let alive = true
const attempted = new Set<string>()
onBeforeUnmount(() => { alive = false })
const movable = computed(() => props.selectedIds.every(id => {
  const ref = props.itemRefs[id], task = data.taskById(ref?.taskId ?? id)
  return task && !ref?.subId && !task.parentTaskId && !data.tasks.some(child => child.parentTaskId === task.id)
}))
function open(value: typeof mode.value) { mode.value = value; errors.value = []; attempted.clear() }
async function apply() {
  if (saving.value) return
  const identity = labels.identity, operation = mode.value
  const selected = props.selectedIds.map(id => ({ id, source: props.itemRefs[id] ?? { taskId: id }, title: props.items.find(t => t.id === id)?.title ?? id }))
  const tags = [...new Set(tagText.value.split(/[,，]/).map(t => t.trim()).filter(Boolean))]
  if (operation === 'tags' && !tags.length) { errors.value = ['请输入至少一个标签']; return }
  if (operation === 'move' && !movable.value) { errors.value = ['有关联父子关系的任务请先解除关系；内嵌子项随主任务移动']; return }
  saving.value = true; errors.value = []
  const succeeded = new Set<string>()
  for (const item of selected) {
    if (!alive || identity !== labels.identity) break
    try {
      const { taskId, subId } = item.source
      const task = data.taskById(taskId)
      if (!task || (subId && !data.subById(taskId, subId))) throw new Error('任务已不存在')
      if (operation === 'tags') {
        if (!labels.ready) throw new Error('标签尚未读取成功，请稍后重试')
        const current = labels.get(taskId, subId)
        await labels.save({ ...current, tags: removeTags.value ? current.tags.filter(tag => !tags.includes(tag)) : [...new Set([...current.tags, ...tags])] })
      } else {
        if (data.taskSaveState(taskId)==='saving' || (data.taskSaveState(taskId)==='error' && attempted.has(taskId))) await data.flushTask(taskId)
        if (!alive || identity !== labels.identity) break
        if (data.taskSaveState(taskId) === 'error') throw new Error('已有未保存或冲突的修改，请先在详情中处理')
        attempted.add(taskId)
        if (operation === 'priority') {
          if (subId) data.updateSub(taskId, subId, { pr: priority.value })
          else data.patchTask(taskId, { pr: priority.value })
        } else if (operation === 'complete') {
          if (subId) data.setSubState(taskId, subId, '已完成')
          else data.patchTask(taskId, { status: '已完成' })
        } else if (operation === 'move') {
          if (!project.value && task.owner !== session.userId) throw new Error('仅能把自己负责的任务移入个人收集箱')
          data.patchTask(taskId, { project: project.value, groupId: '' })
        }
        await data.flushTask(taskId)
        if (data.taskSaveState(taskId) === 'error') throw new Error(data.taskSaveError(taskId) || '保存失败，修改已保留')
      }
      succeeded.add(item.id)
    } catch (error) { if (alive && identity === labels.identity) errors.value.push(`${item.title}：${error instanceof Error ? error.message : '未保存，请重试'}`) }
  }
  saving.value = false
  if (!alive || identity !== labels.identity) return
  emit('update:selectedIds', props.selectedIds.filter(id => !succeeded.has(id)))
  if (succeeded.size) toast(`已更新 ${succeeded.size} 项任务，筛选和四象限已同步`, 'ri-check-line')
  if (!errors.value.length) mode.value = ''
}
</script>
<template>
  <div class="batch-actions" aria-label="批量任务操作">
    <WaoButton :disabled="!selectedIds.length" @click="open('tags')">设置标签</WaoButton>
    <WaoButton :disabled="!selectedIds.length" @click="open('priority')">优先级</WaoButton>
    <WaoButton :disabled="!selectedIds.length" @click="open('complete')">完成任务</WaoButton>
    <WaoButton :disabled="!selectedIds.length || !movable" title="内嵌子项随主任务移动；有关联父子关系的任务需先解除关系" @click="open('move')">移动清单</WaoButton>
    <SettingsDialogShell v-if="mode" :title="`批量${{tags:'设置标签',priority:'修改优先级',complete:'完成任务',move:'移动清单'}[mode]}`" width="480px" height="auto" @close="!saving && (mode='')">
      <form @submit.prevent="apply">
        <p v-if="mode!=='move'">将处理 {{ selectedIds.length }} 项选中的任务；未选中的子任务保持原状。</p>
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
