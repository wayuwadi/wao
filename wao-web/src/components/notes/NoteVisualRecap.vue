<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import { fetchVisualRecap, startVisualRecap, type NoteVisualRecap } from '@/api/noteVisualRecap'
import { companionError } from '@/api/noteAiCompanion'
import { recapDocument } from '@/utils/noteVisualRecap'
const props = defineProps<{ noteId: string; rev: number; blocked: boolean }>()
defineEmits<{ close: [] }>()
const result = ref<NoteVisualRecap>(),
  loading = ref(true),
  sending = ref(false),
  error = ref(''),
  original = ref(false)
const dark = ref(false)
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let requestId = ''
const running = computed(() => sending.value || result.value?.status === 'RUNNING')
const documentHtml = computed(() => recapDocument(result.value?.html || '', dark.value))
function schedule() {
  clearTimeout(timer)
  if (!disposed && result.value?.status === 'RUNNING') timer = setTimeout(() => void load(), 3000)
}
async function load() {
  error.value = ''
  try {
    const value = await fetchVisualRecap(props.noteId)
    if (!disposed) result.value = value
    schedule()
  } catch (e) {
    if (!disposed) error.value = companionError(e)
  } finally {
    if (!disposed) loading.value = false
  }
}
async function generate() {
  if (running.value || props.blocked) return
  sending.value = true
  error.value = ''
  original.value = false
  requestId ||= crypto.randomUUID().replace(/-/g, '')
  try {
    const value = await startVisualRecap(props.noteId, props.rev, requestId)
    if (!disposed) result.value = value
    requestId = ''
    schedule()
  } catch (e) {
    if (!disposed) error.value = companionError(e)
  } finally {
    if (!disposed) sending.value = false
  }
}
onMounted(() => {
  dark.value = getComputedStyle(document.documentElement).colorScheme.includes('dark')
  void load()
})
onBeforeUnmount(() => {
  disposed = true
  clearTimeout(timer)
})
</script>
<template>
  <SettingsDialogShell
    title="图文整理"
    description="把小记梳理成一页有重点、有图示的阅读内容"
    width="1000px"
    height="min(920px, calc(100dvh - 32px))"
    @close="$emit('close')"
  >
    <div class="recap-toolbar">
      <span v-if="result?.sourceRev">来源版本 {{ result.sourceRev }} · 原文保留</span>
      <WaoButton v-if="result?.html" @click="original = !original">{{
        original ? '查看图文版' : '对照原文'
      }}</WaoButton>
      <WaoButton v-if="result?.html" @click="dark = !dark">{{
        dark ? '浅色阅读' : '深色阅读'
      }}</WaoButton>
    </div>
    <p v-if="error" role="alert">{{ error }} <WaoButton @click="load">重新读取</WaoButton></p>
    <p v-if="props.blocked" role="status">请先保存当前编辑，再生成图文整理。</p>
    <p v-if="result?.stale" role="status">原文已有更新，当前展示的是此前版本的整理。</p>
    <p v-if="loading" role="status">正在读取整理结果…</p>
    <div v-else-if="running" class="recap-empty" role="status">
      <h3>正在理解内容、梳理关系与设计版式…</h3>
      <p>可以关闭窗口，稍后回来查看。</p>
    </div>
    <div v-else-if="result?.status === 'FAILED'" class="recap-empty" role="alert">
      <h3>本次整理未完成</h3>
      <p>{{ result.message }}</p>
    </div>
    <pre v-else-if="original" class="recap-original">{{ result?.sourceText }}</pre>
    <iframe
      v-else-if="result?.html"
      class="recap-frame"
      title="小记图文整理阅读页"
      sandbox=""
      referrerpolicy="no-referrer"
      :srcdoc="documentHtml"
    />
    <div v-else-if="!error" class="recap-empty">
      <i class="ri-layout-masonry-line" aria-hidden="true" />
      <h3>让文字的关系看得见</h3>
      <p>
        提炼重点，用流程、时间线或对比卡片组织内容。<br />根据这篇小记选择版式，保留原意与原文。
      </p>
    </div>
    <template #footer
      ><WaoButton @click="$emit('close')">关闭</WaoButton
      ><WaoButton
        variant="primary"
        :disabled="loading || !!error || blocked"
        :loading="running"
        @click="generate"
        >{{
          running
            ? '整理中…'
            : result?.status === 'FAILED'
              ? '重试整理'
              : result?.html
                ? '重新整理'
                : '生成图文整理'
        }}</WaoButton
      ></template
    >
  </SettingsDialogShell>
</template>
<style scoped>
.recap-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--wao-space-1);
  margin-bottom: var(--wao-space-2);
  color: var(--wao-text-3);
}
.recap-toolbar span {
  margin-right: auto;
}
.recap-frame {
  width: 100%;
  height: 65dvh;
  min-height: 320px;
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-card);
  background: var(--wao-bg-sub);
}
.recap-original {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.8;
  padding: var(--wao-space-3);
  background: var(--wao-bg-sub);
  border-radius: var(--wao-radius-card);
}
.recap-empty {
  text-align: center;
  padding: var(--wao-space-4);
  margin: var(--wao-space-4) auto;
  color: var(--wao-text-3);
}
.recap-empty h3 {
  color: var(--wao-text);
}
.recap-empty i {
  font-size: 36px;
  color: var(--wao-primary-text);
}
</style>
