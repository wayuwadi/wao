<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import TaskCaptureFields from './TaskCaptureFields.vue'
import { emptyCapture, capturePayload } from '@/utils/taskCapture'
import { useTaskLabelsStore } from '@/stores/taskLabels'
import type { Priority } from '@/types'
import { useInputComposition } from '@/composables/inputComposition'

const props = defineProps<{ priority: Priority; projectId: string; label: string }>()
const emit = defineEmits<{ close: [] }>()
const session = useSessionStore(), data = useDataStore()
const labels=useTaskLabelsStore(), fields=ref(emptyCapture(props.projectId,props.priority))
const input = ref<HTMLInputElement>()
const confirmed=ref(false)
const title = ref(''), id = ref(''), error = ref(''), saving = ref(false)
const composition = useInputComposition()
const key = computed(() => `wao-quadrant-draft:${session.account?.accountId}:${session.userId}:${props.projectId}:${props.priority}`)
let generation = 0
onBeforeUnmount(() => { generation++ })
watch(key, () => {
  generation++
  confirmed.value=false;title.value = ''; id.value = ''; error.value = ''; saving.value = false
  composition.end()
  try { const draft = JSON.parse(localStorage.getItem(key.value) || '{}'); title.value = draft.title || ''; id.value = draft.id || ''; confirmed.value=!!draft.saved; fields.value={...emptyCapture(props.projectId,props.priority),...draft.fields} }
  catch { error.value = '无法读取草稿' }
}, { immediate: true, flush: 'sync' })
function protect() {
  id.value ||= crypto.randomUUID().replace(/-/g, '')
  try { localStorage.setItem(key.value, JSON.stringify({ title: title.value, id: id.value, fields:fields.value,saved:confirmed.value })) }
  catch { error.value = '无法保护草稿，请保存后再离开' }
}
watch(fields,protect,{deep:true})
async function submit() {
  if (!title.value.trim() || saving.value || composition.active()) return
  protect(); saving.value = true; error.value = ''
  const retryingLabels = confirmed.value
  const attempt = generation
  const active = () => attempt === generation && key.value === draftKey
  const draftKey = key.value, taskId = id.value, taskTitle = title.value.trim()
  const form = input.value?.form, restoreInput = !!form?.contains(document.activeElement)
  try {
    const payload={...capturePayload(fields.value),title:taskTitle,desc:'',owner:session.userId}
    const existing = data.taskById(taskId)
    if (confirmed.value) { /* Retry only unsaved labels after acknowledged creation. */
    } else if (existing) {
      data.patchTask(taskId, payload)
      await data.flushTask(taskId)
      if (data.taskSaveState(taskId) !== 'saved') throw new Error('任务未保存，请重试')
    } else {
      const result = await data.addTask(payload, taskId)
      if (!result.persisted) throw new Error('任务未保存，请重试')
    }
    if(!active()) return
    confirmed.value=true;protect()
    const tags=[...new Set(fields.value.tags.split(/[,，]/).map(t=>t.trim()).filter(Boolean))]
    if(tags.length || retryingLabels){if(!labels.ready)await labels.load();if(!active())return;if(!labels.ready)throw new Error('任务已保存，标签读取失败');await labels.save({...labels.get(taskId),tags});if(!active())return}
    localStorage.removeItem(draftKey)
    if (active()) { title.value = ''; id.value = ''; confirmed.value=false }
  } catch (e) {
    if (active()) error.value = e instanceof Error ? e.message : '任务未保存，请重试'
  } finally {
    if (active()) {
      saving.value = false; await nextTick()
      if (active() && restoreInput && (document.activeElement === document.body || form?.contains(document.activeElement))) {
        const target = input.value?.disabled ? form?.querySelector<HTMLButtonElement>('button:not(:disabled)') : input.value
        target?.focus({ preventScroll: true })
      }
    }
  }
}
function leave(event: FocusEvent) {
  if (!title.value.trim() && !saving.value && !(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null)) emit('close')
}
function inputKey(event: KeyboardEvent) {
  if (composition.guard(event)) return
  if (event.key === 'Enter') { event.preventDefault(); void submit() }
  if (event.key === 'Escape' && !saving.value) { event.preventDefault(); emit('close') }
}
onMounted(() => input.value?.focus())
</script>

<template>
  <form class="quadrant-quick-add" :aria-label="`在${label}添加任务`" @submit.prevent="submit" @focusout="leave" @compositionstart="composition.start" @compositionend="composition.end" @keydown="composition.guard">
    <div class="input-row">
      <input ref="input" v-model="title" :aria-label="`${label}任务名称`" placeholder="输入任务名称，回车添加" :disabled="saving || confirmed" @input="protect" @keydown="inputKey" />
      <button :disabled="saving || !title.trim()">{{ saving ? '保存中…' : confirmed ? '重试标签' : error ? '重试' : '添加' }}</button>
      <button type="button" aria-label="收起添加任务" :disabled="saving" @click="emit('close')"><i class="ri-close-line" aria-hidden="true" /></button>
    </div>
    <TaskCaptureFields v-model="fields" :title="title" :saved="confirmed" :disabled="saving" @title="title=$event;protect()" />
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
