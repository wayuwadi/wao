<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { labelFeedback } from '@/composables/taskChangeFeedback'
import { useTaskLabelsStore } from '@/stores/taskLabels'
import CellMenu from './CellMenu.vue'
const props = defineProps<{ taskId: string; subId?: string; autoOpen?: boolean; inline?: boolean }>()
const emit=defineEmits<{done:[]}>()
const labels = useTaskLabelsStore()
const editing = ref(false), draft = ref(''), revision = ref(0), saving = ref(false), error = ref('')
const input = ref<HTMLInputElement | null>(null)
const trigger = ref<HTMLButtonElement | null>(null)
const draftTags = computed(()=>draft.value.split(/[,，]/).map(s=>s.trim()).filter(Boolean))
const current = computed(() => labels.get(props.taskId, props.subId))
const key = computed(() => `wao-task-label-draft:${labels.identity}:${props.taskId}:${props.subId ?? ''}`)
watch(key, () => {
 editing.value = false; error.value = ''; saving.value = false
 try {
  const saved = localStorage.getItem(key.value)
  if (saved) { const value = JSON.parse(saved); draft.value = value.text; revision.value = value.revision; editing.value = true }
 } catch { error.value = '标签草稿读取失败，请保持页面并重试' }
 if (!labels.ready && !labels.loading) void labels.load()
}, { immediate: true })
watch(() => props.autoOpen && labels.ready, ready => { if (ready && !editing.value) begin() }, { immediate:true })
function focusInput() { input.value?.focus({ preventScroll: true }) }
function begin() {
 draft.value = current.value.tags.join('，'); revision.value = current.value.revision
 try { const saved = localStorage.getItem(key.value); if (saved) { const value = JSON.parse(saved); draft.value = value.text; revision.value = value.revision } }
 catch { error.value = '标签草稿读取失败，请保持页面并重试' }
 editing.value = true
 if (props.autoOpen) void nextTick(focusInput)
}
function protect() {
 try { localStorage.setItem(key.value, JSON.stringify({ text: draft.value, revision: revision.value })) }
 catch { error.value = '无法保护本机草稿，请保持页面直到保存成功' }
}
function toggle(tag: string) { const tags = draftTags.value; draft.value = (tags.includes(tag) ? tags.filter(t=>t!==tag) : [...tags,tag]).join('，'); protect() }
async function save() {
 if (saving.value) return
 const identity = key.value, draftKey = key.value
 saving.value = true; error.value = ''; protect()
 try {
  const before={...current.value,tags:[...current.value.tags]}
  const after=await labels.save({ taskId: props.taskId, subId: props.subId ?? '', revision: revision.value, tags: [...new Set(draft.value.split(/[,，]/).map(s => s.trim()).filter(Boolean))] })
  localStorage.removeItem(draftKey)
  if (key.value === identity) { labelFeedback(before,after); editing.value = false; emit('done'); void nextTick(() => trigger.value?.focus()) }
 } catch (e: any) { if (key.value === identity) error.value = e.response?.data?.message || '标签未保存，草稿已保留，请重试' }
 finally { if (key.value === identity) saving.value = false }
}
async function reload() { await labels.load(); if (!labels.error) { revision.value = current.value.revision; protect() } }
function dismiss() { if (saving.value) return; protect(); editing.value = false }
function cancel() { if (saving.value) return; try { localStorage.removeItem(key.value); editing.value = false; error.value = ''; trigger.value?.focus(); emit('done') } catch { error.value = '无法清除草稿，请重试' } }
</script>
<template>
 <div class="task-labels" :class="{inline}">
  <span v-for="tag in current.tags" :key="tag" class="label-chip"># {{ tag }}</span>
  <button v-if="!autoOpen || !editing" ref="trigger" type="button" :disabled="!labels.ready" aria-haspopup="dialog" :aria-expanded="editing" @click="editing ? dismiss() : begin()"><i class="ri-price-tag-3-line"></i> 标签</button>
  <component :is="autoOpen ? 'div' : CellMenu" v-if="editing" v-bind="autoOpen ? {} : { anchor: trigger, minWidth: 300, customContent: true }" class="label-popover" @close="dismiss" @ready="focusInput">
  <form role="dialog" aria-label="编辑任务标签" @submit.prevent="save" @keydown.esc.stop.prevent="autoOpen ? cancel() : dismiss()">
   <strong>标签</strong>
   <input ref="input" v-model="draft" aria-label="任务标签，用逗号分隔" placeholder="输入标签，用逗号分隔" maxlength="820" :disabled="saving" @input="protect" />
   <div v-if="labels.catalog.length || draftTags.length" class="suggestions"><button v-for="tag in [...new Set([...labels.catalog,...draftTags])]" :key="tag" type="button" :aria-pressed="draftTags.includes(tag)" :disabled="saving" @click="toggle(tag)"><i :class="draftTags.includes(tag)?'ri-checkbox-line':'ri-checkbox-blank-line'"></i># {{ tag }}</button></div>
   <div v-if="error || labels.error" class="label-error" role="alert">{{ error || labels.error }} <button type="button" :disabled="saving" @click="reload">重新读取（保留草稿）</button></div>
   <footer><button type="button" :disabled="saving" @click="cancel">取消</button><button class="save-labels" type="submit" :disabled="saving || !labels.ready">{{ saving ? '保存中…' : '保存' }}</button></footer>
  </form>
  </component>
  <div v-if="!editing && (error || labels.error)" class="label-error" role="alert">{{ error || labels.error }} <button type="button" :disabled="saving" @click="reload">重新读取（保留草稿）</button></div>
 </div>
</template>
<style scoped>
.task-labels { position:relative; min-height:32px; display:flex; flex-wrap:wrap; align-items:center; gap:var(--wao-space-1); margin-block:var(--wao-space-1); font-family:var(--wao-font); font-size:var(--wao-font-body); line-height:1.5; font-weight:400; }
.label-popover { box-sizing:border-box; width:300px; max-width:calc(100vw - 16px); }
.label-chip { background:var(--wao-primary-soft); color:var(--wao-primary-text); border-radius:var(--wao-radius-pill); padding:2px var(--wao-space-1); font-size:var(--wao-font-caption); }
button { border:0; background:transparent; color:var(--wao-primary-text); font:inherit; cursor:pointer; min-height:32px; }
button:disabled { opacity:.5; cursor:default; }
form { display:flex; flex-direction:column; gap:var(--wao-space-1); min-width:0; padding:var(--wao-space-1); color:var(--wao-text); font:400 var(--wao-font-body)/1.5 var(--wao-font); }
input { width:100%; box-sizing:border-box; min-width:0; padding:var(--wao-space-1); border:1px solid var(--wao-line-control); border-radius:var(--wao-radius-control); background:var(--wao-bg-card); color:var(--wao-text); font:inherit; }
.suggestions { display:flex; flex-direction:column; width:100%; max-height:160px; overflow:auto; }
.suggestions button { display:flex; align-items:center; gap:var(--wao-space-1); text-align:left; color:var(--wao-text); border-radius:var(--wao-radius-control); min-height:34px; padding:6px var(--wao-space-1); overflow-wrap:anywhere; }
.suggestions button:hover { background:var(--wao-bg-hover); }
.suggestions button[aria-pressed=true] { color:var(--wao-primary-text); background:var(--wao-primary-soft); }
footer { display:flex; justify-content:flex-end; gap:var(--wao-space-1); border-top:1px solid var(--wao-line); padding-top:var(--wao-space-1); }
.save-labels { background:var(--wao-primary-soft); border-radius:var(--wao-radius-control); padding-inline:var(--wao-space-2); }
.label-error { color:var(--wao-danger-strong); width:100%; }
button:focus-visible,input:focus-visible { outline:2px solid var(--wao-primary); outline-offset:2px; }
@media(pointer:coarse) { button { min-height:44px; } }
</style>
