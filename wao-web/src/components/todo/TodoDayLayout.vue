<script setup lang="ts">
import { ref, useId, watch } from 'vue'
import WaoButton from '@/components/ui/WaoButton.vue'
const props = withDefaults(defineProps<{ primaryLabel?: string; secondaryLabel?: string; resetKey?: string; routineRemaining?: number | null; routineCountState?: 'loading' | 'ready' | 'error'; noteCount?: number }>(), {
  primaryLabel: '当天任务',
  secondaryLabel: '日常与小记',
  routineCountState: 'loading',
})
const active = ref<'routine' | 'notes' | null>(null)
const panelId = useId()
const routineButton = ref<InstanceType<typeof WaoButton> | null>(null)
const notesButton = ref<InstanceType<typeof WaoButton> | null>(null)
watch(() => props.resetKey, () => { active.value = null })
function toggle(panel: 'routine' | 'notes') { active.value = active.value === panel ? null : panel }
function close() {
  const button = active.value === 'routine' ? routineButton.value : notesButton.value
  active.value = null
  ;(button?.$el as HTMLButtonElement | undefined)?.focus()
}
</script>
<template>
  <div class="todo-day-layout">
    <div class="day-columns" :class="{ 'side-open': active }">
      <section class="day-primary" :aria-label="primaryLabel"><slot /></section>
      <aside class="day-secondary" :aria-label="secondaryLabel" @keydown.esc.stop.prevent="close">
        <nav class="day-side-rail" aria-label="当天辅助面板">
          <WaoButton :id="`${panelId}-routine-toggle`" ref="routineButton" variant="ghost" :aria-label="routineCountState === 'error' ? '每日固定，数量暂无法读取，展开后重试' : routineCountState === 'loading' || routineRemaining == null ? '每日固定，正在读取数量' : `每日固定，${routineRemaining} 项未做`" :title="routineCountState === 'error' ? '数量暂无法读取，展开后重试' : '当天早晨与晚上待处理的固定事项'" :aria-expanded="active === 'routine'" :aria-controls="`${panelId}-routine`" @click="toggle('routine')"><i class="ri-repeat-line" aria-hidden="true"></i><span>每日固定</span><small class="day-side-count" aria-hidden="true">{{ routineCountState === 'error' ? '!' : routineCountState === 'loading' || routineRemaining == null ? '…' : routineRemaining }}</small></WaoButton>
          <WaoButton :id="`${panelId}-notes-toggle`" ref="notesButton" variant="ghost" :aria-label="noteCount == null ? '随手记，正在读取数量' : `随手记，${noteCount} 条记录`" :aria-expanded="active === 'notes'" :aria-controls="`${panelId}-notes`" @click="toggle('notes')"><i class="ri-edit-box-line" aria-hidden="true"></i><span>随手记</span><small class="day-side-count" aria-hidden="true">{{ noteCount ?? '…' }}</small></WaoButton>
        </nav>
        <div v-show="active" class="day-side-panels">
          <header class="day-side-heading"><strong>{{ active === 'routine' ? '每日固定' : '随手记' }}</strong><WaoButton variant="ghost" aria-label="收起当天辅助面板" @click="close"><i class="ri-arrow-right-s-line" aria-hidden="true"></i>收起</WaoButton></header>
          <section v-show="active === 'routine'" :id="`${panelId}-routine`" :aria-labelledby="`${panelId}-routine-toggle`"><slot name="routine" /></section>
          <section v-show="active === 'notes'" :id="`${panelId}-notes`" :aria-labelledby="`${panelId}-notes-toggle`"><slot name="notes" /></section>
        </div>
      </aside>
    </div>
  </div>
</template>
<style scoped>
.todo-day-layout { container: todo-day-layout / inline-size; min-width: 0; }
.day-columns { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: start; gap: var(--wao-space-2); }
.day-columns.side-open { grid-template-columns: minmax(0, 2fr) minmax(300px, 1fr); }
.day-primary { display: grid; min-width: 0; gap: var(--wao-space-2); }
.day-secondary { display: flex; flex-direction: row-reverse; align-items: start; min-width: 0; gap: var(--wao-space-1); }
.day-side-rail { display: flex; flex-direction: column; gap: var(--wao-space-1); border-left: 1px solid var(--wao-line); padding-left: var(--wao-space-1); }
.day-side-rail button { flex-direction: column; padding: var(--wao-space-1); min-width: 40px; }
.day-side-rail span { writing-mode: vertical-rl; letter-spacing: .15em; }
.day-side-count { min-width: 2ch; padding-inline: 4px; border-radius: var(--wao-radius-control); background: var(--wao-bg-sub); color: var(--wao-text-2); font-size: 12px; font-variant-numeric: tabular-nums; line-height: 1.6; }
.day-side-rail button[aria-expanded='true'] { background: var(--wao-primary-soft); }
.day-side-panels { flex: 1; min-width: 0; }
.day-side-heading { display: flex; justify-content: space-between; align-items: center; gap: var(--wao-space-1); margin-bottom: var(--wao-space-1); color: var(--wao-text-2); font-size: 13px; }
@container todo-day-layout (max-width: 800px) {
  .day-columns, .day-columns.side-open { grid-template-columns: minmax(0, 1fr); }
  .day-secondary { grid-row: 1; flex-direction: column; align-items: stretch; }
  .day-side-rail { flex-direction: row; justify-content: flex-end; border-left: 0; padding-left: 0; }
  .day-side-rail button { flex-direction: row; min-height: 44px; }
  .day-side-rail span { writing-mode: horizontal-tb; letter-spacing: normal; }
}
</style>
