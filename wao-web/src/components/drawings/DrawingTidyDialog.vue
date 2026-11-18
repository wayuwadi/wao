<script setup lang="ts">
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import { renderDrawingImage } from '@/utils/drawingImageExport'
import type { DrawingTidyCanvas, DrawingTidyPreparation } from '@/utils/drawingTidyClient'
import type { DrawingTidyRequest } from '@/utils/drawingTidy'

const props = defineProps<{
  mode: 'route' | 'layout'
  canvas: DrawingTidyCanvas
  disabled: boolean
}>()
const emit = defineEmits<{ close: [] }>()
const hasSelection = props.canvas.hasSelection()
const scope = ref<DrawingTidyRequest['scope']>(hasSelection ? 'selection' : 'all')
const layout = ref<NonNullable<DrawingTidyRequest['layout']>>('right')
const preview = shallowRef<DrawingTidyPreparation>()
const beforeUrl = ref('')
const afterUrl = ref('')
const busy = ref(false)
const error = ref('')
let request: AbortController | undefined

function clearPreview() {
  URL.revokeObjectURL(beforeUrl.value)
  URL.revokeObjectURL(afterUrl.value)
  beforeUrl.value = ''
  afterUrl.value = ''
  preview.value = undefined
}

async function generate() {
  request?.abort()
  clearPreview()
  const controller = new AbortController()
  request = controller
  busy.value = true
  error.value = ''
  try {
    const result = await props.canvas.prepare({ mode: props.mode, scope: scope.value, layout: layout.value }, controller.signal)
    if (controller.signal.aborted) return
    if (result.changed) {
      const [before, after] = await Promise.all([
        renderDrawingImage(result.before, 'png', 1200),
        renderDrawingImage(result.after, 'png', 1200),
      ])
      if (controller.signal.aborted) return
      beforeUrl.value = URL.createObjectURL(before)
      afterUrl.value = URL.createObjectURL(after)
    }
    preview.value = result
  } catch (cause) {
    if (controller.signal.aborted) return
    error.value = cause instanceof Error ? cause.message : '预览生成失败，请重试。'
  } finally {
    if (!controller.signal.aborted) busy.value = false
  }
}

function apply() {
  if (!preview.value?.changed || props.disabled) return
  try {
    preview.value.apply()
    emit('close')
  } catch (cause) {
    clearPreview()
    error.value = cause instanceof Error ? cause.message : '整理未能应用，请重试。'
  }
}

watch([scope, layout], generate, { immediate: true })
onBeforeUnmount(() => {
  request?.abort()
  clearPreview()
})
</script>

<template>
  <SettingsDialogShell :title="mode === 'route' ? '只整理连线' : '重新布局'" icon="ri-node-tree" @close="emit('close')">
    <div class="tidy-settings">
      <label>
        <span>范围</span>
        <select v-model="scope" aria-label="整理范围">
          <option v-if="hasSelection" value="selection">当前选区</option>
          <option value="all">整张画板</option>
        </select>
      </label>
      <label v-if="mode === 'layout'">
        <span>布局</span>
        <select v-model="layout" aria-label="布局方式">
          <option value="right">横向流程</option>
          <option value="down">纵向流程</option>
          <option value="tree">树形</option>
        </select>
      </label>
    </div>
    <p v-if="busy" role="status">正在生成整理预览…</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <template v-else-if="preview">
      <div v-if="preview.changed" class="tidy-previews">
        <figure><figcaption>整理前</figcaption><img :src="beforeUrl" alt="整理前的草图" /></figure>
        <figure><figcaption>整理后</figcaption><img :src="afterUrl" alt="整理后的草图" /></figure>
      </div>
      <p role="status">
        {{ preview.changed ? `移动 ${preview.moved} 个图形，重新布线 ${preview.routed} 条。` : '当前没有可安全改善的布局。' }}
        <span v-if="preview.skipped">保留了 {{ preview.skipped }} 项无法安全调整的内容。</span>
      </p>
    </template>
    <template #footer>
      <WaoButton variant="ghost" @click="emit('close')">取消</WaoButton>
      <WaoButton v-if="error" @click="generate()">重新预览</WaoButton>
      <WaoButton variant="primary" :disabled="busy || disabled || !preview?.changed" @click="apply">应用整理</WaoButton>
    </template>
  </SettingsDialogShell>
</template>

<style scoped>
.tidy-settings {
  display: flex;
  flex-wrap: wrap;
  gap: var(--wao-space-2);
  margin-bottom: var(--wao-space-2);
}
.tidy-settings label {
  display: flex;
  align-items: center;
  gap: var(--wao-space-1);
}
.tidy-settings select {
  min-height: var(--wao-control-height);
  padding-inline: var(--wao-space-1);
  color: var(--wao-text);
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
}
.tidy-previews {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 280px), 1fr));
  gap: var(--wao-space-2);
}
.tidy-previews figure {
  display: flex;
  flex-direction: column;
  min-width: 0;
  margin: 0;
  background: var(--wao-bg-sub);
  border: 1px solid var(--wao-line);
  border-radius: var(--wao-radius-control);
  overflow: hidden;
}
.tidy-previews figcaption { padding: var(--wao-space-1); }
.tidy-previews img {
  width: 100%;
  height: clamp(160px, 32vh, 350px);
  object-fit: contain;
}
@media (pointer: coarse) {
  .tidy-settings select { min-height: 44px; }
}
</style>
