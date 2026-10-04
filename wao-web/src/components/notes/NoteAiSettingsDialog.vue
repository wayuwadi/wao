<script setup lang="ts">
import NoteAiSettingsForm from './NoteAiSettingsForm.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
defineProps<{ open: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; saved: [] }>()
</script>
<template>
  <SettingsDialogShell
    v-if="open"
    title="小记 AI 设置"
    description="保存后用于下一次生成，切换设置项会保留草稿。"
    @close="emit('update:open', false)"
    ><NoteAiSettingsForm closable @close="emit('update:open', false)" @saved="emit('saved')"
  /></SettingsDialogShell>
</template>
