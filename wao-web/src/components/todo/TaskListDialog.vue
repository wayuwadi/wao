<script setup lang="ts">
import { ref, watch } from 'vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoField from '@/components/ui/WaoField.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import type { Project } from '@/types'
const props = defineProps<{ project?: Project }>()
const emit = defineEmits<{ close: []; saved: [id: string] }>()
const data = useDataStore(), session = useSessionStore()
const identity = session.userId
const name = ref(props.project?.name ?? ''), busy = ref(false), error = ref('')
watch(() => session.userId, () => emit('close'))
async function save() {
  if (busy.value || !name.value.trim()) return
  busy.value = true; error.value = ''
  try {
    let id = props.project?.id
    if (id) {
      if (!await data.updateProject(id, { name: name.value.trim() })) throw new Error()
    } else id = (await data.addProject({ name: name.value.trim(), area: '', goal: '', visAll: false })).id
    if (session.userId === identity) emit('saved', id)
  } catch { error.value = '保存失败，输入已保留，请重试。' }
  finally { busy.value = false }
}
</script>
<template>
  <SettingsDialogShell :title="project ? '重命名清单' : '新建清单'" width="480px" height="auto" :close-disabled="busy" @close="emit('close')">
    <form id="task-list-form" @submit.prevent="save">
      <WaoField label="清单名称" required><input v-model="name" aria-label="清单名称" autofocus maxlength="100" :disabled="busy" /></WaoField>
      <p v-if="error" role="alert">{{ error }}</p>
    </form>
    <template #footer><WaoButton :disabled="busy" @click="emit('close')">取消</WaoButton><WaoButton form="task-list-form" type="submit" :disabled="busy || !name.trim()">{{ busy ? '保存中…' : '保存' }}</WaoButton></template>
  </SettingsDialogShell>
</template>
<style scoped>
input { width:100%; padding:var(--wao-space-1); color:var(--wao-text); background:var(--wao-bg-sub); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); font:inherit; }
</style>
