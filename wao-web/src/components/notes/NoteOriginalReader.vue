<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { fetchNoteOriginals, type NoteOriginals } from '@/api/noteOriginals'
import { resolvedRichContent } from '@/utils/richDoc'
import type { RichDocContent } from '@/types'
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const props = defineProps<{ docId: string; memberId: string; currentRev?: number; imageReferences?: boolean; activeImageReference?: number | null }>()
const emit = defineEmits<{ 'source-change': [key: string]; ready: []; 'reading-content': [content: RichDocContent | null]; 'image-reference': [index: number] }>()
const editor = ref<{ focusImageReference: (index: number) => boolean } | null>(null)
defineExpose({ focusImageReference: (index: number) => editor.value?.focusImageReference(index) ?? false })
const content = ref<NoteOriginals | null>(null), error = ref(''), loading = ref(false), selected = ref<'before' | 'initial'>('before')
let generation = 0
const source = computed(() => selected.value === 'initial' ? content.value?.initial : content.value?.before ?? content.value?.initial)
const label = computed(() => source.value?.sourceType === 'LEGACY_BEFORE_AI' ? '历史留存的润色前内容' : selected.value === 'initial' || !content.value?.before ? source.value?.sourceType === 'USER_INITIAL' || source.value?.sourceType === 'DRAFT_BEFORE_AI' ? '最初记录' : '首次留存内容' : '本次润色前')
const differentInitial = computed(() => content.value?.before && content.value.initial && (content.value.before.markdown !== content.value.initial.markdown || content.value.before.title !== content.value.initial.title))
const initialLabel = computed(() => ['USER_INITIAL', 'DRAFT_BEFORE_AI'].includes(content.value?.initial?.sourceType ?? '') ? '最初记录' : '首次留存内容')
const rich = computed(() => source.value ? resolvedRichContent({ content: { version: 1, document: source.value.document, markdown: source.value.markdown }, blocks: source.value.blocks }) : null)
async function load() {
 const ticket = ++generation; loading.value = true; error.value = ''
 try { const result = await fetchNoteOriginals(props.docId); if(ticket === generation) content.value = result }
 catch(cause) { if(ticket === generation) error.value = cause instanceof Error ? cause.message : '读取原文失败，请重试' }
 finally { if(ticket === generation) loading.value = false }
}
watch(() => [props.memberId, props.docId, props.currentRev], () => { generation++; content.value = null; selected.value = 'before'; void load() }, { immediate: true })
watch(source, async value => { emit('source-change', value ? `${selected.value}:${value.id}` : 'loading'); await nextTick(); emit('ready') })
watch([rich, loading, error], () => emit('reading-content', loading.value || error.value ? null : rich.value), { immediate: true })
onBeforeUnmount(() => { generation++ })
</script>
<template>
 <section class="note-original-reader" aria-label="只读原文">
  <p v-if="loading" role="status">正在读取原文…</p>
  <p v-else-if="error" role="alert">{{ error }} <button type="button" @click="load">重试读取原文</button></p>
  <template v-else-if="source && rich">
   <div class="source-heading"><strong>{{ label }}</strong><small>{{ source.createdAt }} · 只读</small>
    <div v-if="differentInitial" role="group" aria-label="原文来源"><button type="button" :aria-pressed="selected === 'before'" @click="selected = 'before'">本次润色前</button><button type="button" :aria-pressed="selected === 'initial'" @click="selected = 'initial'">{{ initialLabel }}</button></div>
   </div>
   <p>{{ content?.explanation }}</p><p v-if="content?.appliedRev && currentRev && content.appliedRev !== currentRev">当前内容在该次润色之后有更新；此原文对应最近一次润色。</p>
   <h1>{{ source.title === null ? '标题未留存' : source.title || '未填写标题' }}</h1>
   <MarkdownEditor ref="editor" :model-value="rich.markdown" :document="rich.document" readonly preview-images bare advanced allow-h1 :min-height="0" :image-references="imageReferences" :active-image-reference="activeImageReference" @image-reference="emit('image-reference', $event)" @character-count="emit('ready')" />
  </template>
  <p v-else>没有可用原文。更早的内容没有留存，无法恢复；当前内容仍保留。</p>
 </section>
</template>
<style scoped>
.source-heading { display:flex; flex-wrap:wrap; align-items:center; gap:10px; } small,p { font-size:12px; color:var(--wao-text-weak); line-height:1.6; } button { border:1px solid var(--wao-line-control); border-radius:6px; padding:6px 9px; background:var(--wao-bg-card); color:var(--wao-primary-text); } button[aria-pressed=true] { background:var(--wao-primary-soft); } [role=alert] { color:var(--wao-danger-strong); } h1 { font-size:22px; overflow-wrap:anywhere; }
</style>
