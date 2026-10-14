<script setup lang="ts">
import { useId } from 'vue'
export type ProjectSettingsTab = 'basic' | 'members' | 'more'
defineProps<{ modelValue: ProjectSettingsTab; memberCount: number }>()
const emit = defineEmits<{ 'update:modelValue': [value: ProjectSettingsTab] }>()
const id = useId()
const tabs = [{ id: 'basic', label: '基本信息' }, { id: 'members', label: '项目成员' }, { id: 'more', label: '更多' }] as const
function navigate(event: KeyboardEvent, index: number) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? 2 : (index + (event.key === 'ArrowRight' ? 1 : 2)) % 3
  const tab = tabs[next]!
  emit('update:modelValue', tab.id)
  document.getElementById(`${id}-${tab.id}`)?.focus()
}
</script>
<template>
  <div class="project-settings-layout">
    <div class="settings-tabs" role="tablist" aria-label="项目设置分项">
      <button v-for="(tab, index) in tabs" :id="`${id}-${tab.id}`" :key="tab.id" type="button" role="tab"
        :aria-selected="modelValue === tab.id" :aria-controls="`${id}-${tab.id}-panel`" :tabindex="modelValue === tab.id ? 0 : -1"
        @click="emit('update:modelValue', tab.id)" @keydown="navigate($event, index)">
        {{ tab.label }}<span v-if="tab.id === 'members'" class="member-count">{{ memberCount }}</span>
      </button>
    </div>
    <section v-for="tab in tabs" :id="`${id}-${tab.id}-panel`" :key="tab.id" :hidden="modelValue !== tab.id"
      role="tabpanel" :aria-labelledby="`${id}-${tab.id}`" class="settings-panel">
      <slot :name="tab.id" />
    </section>
  </div>
</template>
<style scoped>
.project-settings-layout { min-width: 0; }
.settings-tabs { display: flex; gap: 24px; padding: 0 26px; border-bottom: 1px solid var(--wao-line); }
.settings-tabs button { display: inline-flex; align-items: center; gap: 7px; min-height: 48px; padding: 0 0 2px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--wao-text-3); font: inherit; cursor: pointer; white-space: nowrap; }
.settings-tabs button[aria-selected='true'] { color: var(--wao-primary-text); border-bottom-color: var(--wao-primary); }
.member-count { font-size: 12px; }
.settings-panel { padding: 24px 26px; min-width: 0; }
.settings-panel[hidden] { display: none; }
@media (max-width: 650px) { .settings-tabs { padding: 0 15px; gap: 22px; } .settings-panel { padding: 20px 15px; } }
</style>
