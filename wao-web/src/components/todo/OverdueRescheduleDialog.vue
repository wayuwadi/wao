<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import type { Task } from '@/types'
import type { ProjectItemRefs } from '@/utils/projectWorkItems'
import { deadlineInfo } from '@/utils/taskDeadline'
import { todayIso } from '@/utils/time'
const props = defineProps<{ items: Task[]; itemRefs: ProjectItemRefs }>()
const emit = defineEmits<{ close: []; saved: [count: number] }>()
const data = useDataStore(), session = useSessionStore(), ui = useUiStore()
const member = session.userId, targetDay = todayIso()
// Freeze the confirmation scope; later search/filter/sync changes must not add tasks.
const targets = props.items.filter(t => deadlineInfo(t.due, targetDay, t.status === '已完成').overdue > 0)
  .map(t => ({ id: t.id, title: t.title, due: t.due, ...(props.itemRefs[t.id] ?? { taskId: t.id }) }))
const groups = [...new Set(targets.map(t => t.taskId))]
const completed = ref(new Set<string>()), attempted = new Set<string>()
const changed = new Set<string>()
const failures = ref<{ taskId: string; message: string }[]>([]), saving = ref(false)
const remaining = computed(() => targets.filter(t => !completed.value.has(t.id)).length)
let alive = true
onBeforeUnmount(() => { alive = false })
watch(() => session.userId, () => emit('close'), { flush: 'sync' })
async function apply() {
  if (saving.value || member !== session.userId) return
  saving.value = true; failures.value = []
  for (const taskId of groups) {
    if (!alive || member !== session.userId) break
    const pending = targets.filter(t => t.taskId === taskId && !completed.value.has(t.id))
    if (!pending.length) continue
    try {
      if (data.movingTask || data.movingSubtasks) throw new Error('任务正在移动，请稍后重试')
      if (data.taskSaveState(taskId) === 'saving' || attempted.has(taskId)) await data.flushTask(taskId)
      if (!alive || member !== session.userId) break
      if (data.taskSaveState(taskId) === 'error') throw new Error(data.taskSaveError(taskId) || '请先处理已有的保存问题')
      if (!attempted.has(taskId)) {
        // Recheck each date and completion state before changing a shared source.
        for (const target of pending) {
          const current = target.subId ? data.subById(taskId, target.subId) : data.taskById(taskId)
          if (!current || current.due !== target.due || ('done' in current ? current.done : current.status === '已完成')) continue
          if (target.subId) data.updateSub(taskId, target.subId, { due: targetDay })
          else data.patchTask(taskId, { due: targetDay })
          changed.add(target.id)
        }
        attempted.add(taskId)
        await data.flushTask(taskId)
      }
      if (!alive || member !== session.userId) break
      if (data.taskSaveState(taskId) === 'error') throw new Error(data.taskSaveError(taskId) || '顺延尚未保存，修改已保留')
      pending.forEach(t => completed.value.add(t.id))
    } catch (error) {
      if (alive && member === session.userId) failures.value.push({ taskId, message: `${pending[0]!.title}：${error instanceof Error ? error.message : '保存失败，请重试'}` })
    }
  }
  saving.value = false
  if (alive && member === session.userId && !failures.value.length) emit('saved', changed.size)
}
</script>
<template>
  <SettingsDialogShell title="顺延至今天" width="480px" height="320px" :close-disabled="saving" @close="emit('close')">
    <div class="reschedule-content">
      <p>当前视图中 {{ targets.length }} 项未完成的已过期任务将顺延至今天。</p>
      <p class="scope-note">仅调整截止日期；未在当前筛选结果中的任务不受影响。</p>
      <div v-if="failures.length" role="alert">
        <p>还有 {{ remaining }} 项尚未确认保存。成功的任务不会重复处理。</p>
        <div v-for="failure in failures" :key="failure.taskId"><p>{{ failure.message }}</p><WaoButton variant="ghost" @click="ui.openTask(failure.taskId)">查看保存问题</WaoButton></div>
      </div>
    </div>
    <template #footer><div class="reschedule-actions"><WaoButton :disabled="saving" @click="emit('close')">关闭</WaoButton><WaoButton variant="primary" :loading="saving" :disabled="!remaining" @click="apply">{{ failures.length ? '重试剩余任务' : '顺延' }}</WaoButton></div></template>
  </SettingsDialogShell>
</template>
<style scoped>
.reschedule-content { padding:var(--wao-space-3); font:400 var(--wao-font-body)/1.7 var(--wao-font); overflow-wrap:anywhere; }
p { margin:0 0 var(--wao-space-1); }.scope-note { color:var(--wao-text-3); font-size:var(--wao-font-help); }
[role=alert] { color:var(--wao-danger-strong); }.reschedule-actions { display:flex; justify-content:flex-end; gap:var(--wao-space-1); }
</style>
