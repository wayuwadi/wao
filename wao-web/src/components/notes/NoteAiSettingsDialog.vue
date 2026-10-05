<script setup lang="ts">
import NoteAiSettingsForm from './NoteAiSettingsForm.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
defineProps<{ open: boolean }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; saved: [] }>()
</script>
<template>
  <SettingsDialogShell
    v-if="open"
    title="小记设置"
    description="编辑、图片与自动整理，都在这里调整。"
    @close="emit('update:open', false)"
    ><NoteAiSettingsForm closable @close="emit('update:open', false)" @saved="emit('saved')"
  /></SettingsDialogShell>
</template>
