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
  <SettingsDialogShell :title="project ? '重命名清单' : '新建清单'" icon="ri-list-check" width="480px" height="auto" :close-disabled="busy" @close="emit('close')">
    <form id="task-list-form" class="list-form" @submit.prevent="save">
      <WaoField v-slot="field" label="清单名称" required hint="用一个简短的名称，整理同一类任务。"><input :id="field.id" v-model="name" aria-label="清单名称" :aria-describedby="field.describedBy" :aria-invalid="field.invalid" required placeholder="例如：工作安排、生活计划" autofocus maxlength="100" :disabled="busy" /></WaoField>
      <p v-if="error" class="save-error" role="alert">{{ error }}</p>
    </form>
    <template #footer><div class="list-actions"><WaoButton :disabled="busy" @click="emit('close')">取消</WaoButton><WaoButton variant="primary" form="task-list-form" type="submit" :disabled="busy || !name.trim()">{{ busy ? '保存中…' : project ? '保存' : '创建清单' }}</WaoButton></div></template>
  </SettingsDialogShell>
</template>
<style scoped>
.list-form { display:grid; gap:var(--wao-space-2); padding:var(--wao-space-3); }
.list-actions { display:flex; justify-content:flex-end; gap:var(--wao-space-1); width:100%; }
.save-error { margin:0; color:var(--wao-danger-strong); font-size:var(--wao-font-help); }
input { box-sizing:border-box; }
input:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
</style>
