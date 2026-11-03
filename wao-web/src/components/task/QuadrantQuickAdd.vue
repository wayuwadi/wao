<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import type { Priority } from '@/types'

const props = defineProps<{ priority: Priority; projectId: string; label: string }>()
const emit = defineEmits<{ close: [] }>()
const session = useSessionStore(), data = useDataStore()
const input = ref<HTMLInputElement>()
const title = ref(''), id = ref(''), error = ref(''), saving = ref(false)
const key = computed(() => `wao-quadrant-draft:${session.account?.accountId}:${session.userId}:${props.projectId}:${props.priority}`)
watch(key, () => {
  title.value = ''; id.value = ''; error.value = ''; saving.value = false
  try { const draft = JSON.parse(localStorage.getItem(key.value) || '{}'); title.value = draft.title || ''; id.value = draft.id || '' }
  catch { error.value = '无法读取草稿' }
}, { immediate: true })
function protect() {
  id.value ||= crypto.randomUUID().replace(/-/g, '')
  try { localStorage.setItem(key.value, JSON.stringify({ title: title.value, id: id.value })) }
  catch { error.value = '无法保护草稿，请保存后再离开' }
}
async function submit() {
  if (!title.value.trim() || saving.value) return
  protect(); saving.value = true; error.value = ''
  const draftKey = key.value, taskId = id.value, taskTitle = title.value.trim()
  try {
    const existing = data.taskById(taskId)
    if (existing) {
      data.patchTask(taskId, { title: taskTitle })
      await data.flushTask(taskId)
      if (data.taskSaveState(taskId) !== 'saved') throw new Error('任务未保存，请重试')
    } else {
      const result = await data.addTask({ title: taskTitle, desc: '', project: props.projectId, pr: props.priority, owner: session.userId, due: '' }, taskId)
      if (!result.persisted) throw new Error('任务未保存，请重试')
    }
    localStorage.removeItem(draftKey)
    if (key.value === draftKey) { title.value = ''; id.value = '' }
  } catch (e) {
    if (key.value === draftKey) error.value = e instanceof Error ? e.message : '任务未保存，请重试'
  } finally {
    if (key.value === draftKey) { saving.value = false; await nextTick(); input.value?.focus() }
  }
}
function leave(event: FocusEvent) {
  if (!title.value.trim() && !saving.value && !(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null)) emit('close')
}
onMounted(() => input.value?.focus())
</script>

<template>
  <form class="quadrant-quick-add" :aria-label="`在${label}添加任务`" @submit.prevent="submit" @focusout="leave">
    <div class="input-row">
      <input ref="input" v-model="title" :aria-label="`${label}任务名称`" placeholder="输入任务名称，回车添加" :disabled="saving" @input="protect" @keydown.enter.prevent="!$event.isComposing && submit()" @keydown.esc.prevent="!saving && emit('close')" />
      <button :disabled="saving || !title.trim()">{{ saving ? '保存中…' : error ? '重试' : '添加' }}</button>
      <button type="button" aria-label="收起添加任务" :disabled="saving" @click="emit('close')"><i class="ri-close-line" aria-hidden="true" /></button>
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
  </form>
</template>

<style scoped>
.quadrant-quick-add { margin: var(--wao-space-1); padding: var(--wao-space-1); border: 1px solid var(--wao-line); border-radius: var(--wao-radius-control); background: var(--wao-bg-card); }
.input-row { display: flex; align-items: center; gap: var(--wao-space-1); }
input { flex: 1; min-width: 0; width: 0; border: 0; background: transparent; color: var(--wao-text); font: inherit; padding: var(--wao-space-1); }
button { flex-shrink: 0; border: 0; border-radius: var(--wao-radius-control); padding: var(--wao-space-1); background: transparent; color: var(--wao-primary-text); font: inherit; cursor: pointer; }
button:disabled { opacity: .5; cursor: default; }
input:focus-visible,button:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 1px; }
p { color: var(--wao-danger-strong); margin: var(--wao-space-1) 0 0; }
</style>
