<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import type { ConfirmAction } from '@/composables/confirmAction'
import SettingsDialogShell from './SettingsDialogShell.vue'
import WaoButton from './WaoButton.vue'

const props = defineProps<{ action: ConfirmAction; theme?: 'light' | 'dark' }>()
const emit = defineEmits<{ close: [] }>()
const busy = ref(false)
const error = ref('')
let alive = true
onBeforeUnmount(() => { alive = false })
async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await props.action.run()
    if (alive) emit('close')
  } catch (cause) {
    if (alive) error.value = cause instanceof Error ? cause.message : '操作未完成，请稍后重试。'
  } finally {
    if (alive) busy.value = false
  }
}
</script>

<template>
  <SettingsDialogShell :title="action.title" :theme="theme" width="480px" height="auto" :icon="action.danger === false ? 'ri-question-line' : 'ri-delete-bin-line'" :close-disabled="busy" @close="!busy && emit('close')">
    <div class="confirmation-copy">
      <p>{{ action.description }}</p>
      <p v-if="error" class="confirmation-error" role="alert">{{ error }}</p>
    </div>
    <template #footer>
      <div class="confirmation-actions">
        <WaoButton :disabled="busy" @click="emit('close')">取消</WaoButton>
        <WaoButton :variant="action.danger === false ? 'primary' : 'danger'" :loading="busy" @click="submit">{{ busy ? '正在处理…' : error ? '重试' : action.confirmLabel || '确认删除' }}</WaoButton>
      </div>
    </template>
  </SettingsDialogShell>
</template>

<style scoped>
.confirmation-copy { padding: var(--wao-space-3); font-size: var(--wao-font-body); overflow-wrap: anywhere; }
.confirmation-copy p { margin: 0; white-space: pre-line; }
.confirmation-copy .confirmation-error { margin-top: var(--wao-space-2); color: var(--wao-danger-strong); }
.confirmation-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: var(--wao-space-1); }
</style>
