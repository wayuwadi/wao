<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import type { TaskStep } from '@/types'
import { subtaskRows, subtaskState } from '@/utils/subtaskTree'

const props = defineProps<{ taskId: string; subs: TaskStep[]; order?: string[]; readOnly?: boolean; saving?: boolean }>()
const emit = defineEmits<{ toggle: [subId: string] }>()
const showDone = ref(false)
const listId = useId()
const rows = computed(() => {
  const keys = new Map(props.subs.map((sub, index) => [sub, sub.id || `${props.taskId}#${index}`]))
  const ranks = new Map(props.order?.map((id, index) => [id, index]))
  const sorted = [...props.subs].sort((a, b) => (ranks.get(keys.get(a)!) ?? Infinity) - (ranks.get(keys.get(b)!) ?? Infinity))
  return subtaskRows(props.taskId, sorted).map(row => ({ ...row, key: keys.get(row.sub)! }))
})
const doneCount = computed(() => rows.value.filter(row => row.sub.done).length)
// 完成的上级仍有未完成下级时保留上级，避免缩进失去上下文。
const visibleRows = computed(() => {
  if (showDone.value) return rows.value
  const keep = new Set<string>(), ancestors: typeof rows.value = []
  for (const row of rows.value) {
    while (ancestors.length && ancestors[ancestors.length - 1]!.depth >= row.depth) ancestors.pop()
    if (!row.sub.done) {
      keep.add(row.key)
      ancestors.forEach(parent => keep.add(parent.key))
    }
    ancestors.push(row)
  }
  return rows.value.filter(row => keep.has(row.key))
})
</script>

<template>
  <div class="todo-checklist">
    <ul :id="listId" aria-label="子事项">
      <li v-for="row in visibleRows" :key="row.key" :style="{ '--sub-depth': Math.min(row.depth, 3) }">
        <label :class="{ done: row.sub.done }">
          <input type="checkbox" :checked="row.sub.done" :disabled="readOnly || saving"
            :aria-label="`${row.sub.done ? '重新打开' : '完成'}子事项：${row.sub.text}`" @change="!readOnly && !saving && emit('toggle', row.key)" />
          <span>{{ row.sub.text }}</span>
        </label>
        <span v-if="subtaskState(row.sub) !== '未开始'" class="sub-state">{{ subtaskState(row.sub) }}</span>
      </li>
    </ul>
    <button v-if="doneCount" type="button" :aria-expanded="showDone" :aria-controls="listId" @click="showDone = !showDone">
      {{ showDone ? '收起已完成子事项' : `已完成 ${doneCount} 项 · 展开` }}
    </button>
  </div>
</template>

<style scoped>
.todo-checklist { min-width: 0; margin: var(--wao-space-1) 0 0 32px; }
ul { list-style: none; padding: 0; margin: 0; }
li { display: flex; align-items: baseline; flex-wrap: wrap; gap: 4px var(--wao-space-1); padding: 5px 0 5px calc(var(--sub-depth) * var(--wao-space-2)); }
label { display: flex; align-items: baseline; gap: var(--wao-space-1); min-width: 0; flex: 1; color: var(--wao-text-2); font-size: 13px; line-height: 1.7; }
label span { min-width: 0; overflow-wrap: anywhere; }
input { flex: none; accent-color: var(--wao-primary); }
.done span { text-decoration: line-through; color: var(--wao-text-3); }
.sub-state, button { color: var(--wao-text-3); font-size: 12px; }
button { min-height: 32px; padding: 4px 0; border: 0; background: transparent; font-family: inherit; cursor: pointer; }
button:focus-visible, input:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }
@media (max-width: 600px) { .todo-checklist { margin-left: 40px; } li { flex-direction: column; align-items: stretch; gap: 0; } label { width: 100%; } .sub-state { margin-left: var(--wao-space-3); } }
@media (pointer: coarse) { label, button { min-height: 44px; align-items: center; } }
</style>
