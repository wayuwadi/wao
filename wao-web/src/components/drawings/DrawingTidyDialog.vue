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
const layout = ref<NonNullable<DrawingTidyRequest['layout']>>('smart')
const preview = shallowRef<DrawingTidyPreparation>()
const beforeUrl = ref('')
const afterUrl = ref('')
const expandFrames = ref(false)
const view = ref<'after' | 'before' | 'compare'>('after')
const zoom = ref(1)
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
    const result = await props.canvas.prepare({
      mode: props.mode, scope: scope.value, layout: layout.value,
      ...(expandFrames.value ? { expandFrames: true } : {}),
    }, controller.signal)
    if (controller.signal.aborted) return
    if (result.changed) {
      const [before, after] = await Promise.all([
        renderDrawingImage(result.before, 'png', 3600),
        renderDrawingImage(result.after, 'png', 3600),
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

watch([scope, layout, expandFrames], generate, { immediate: true })
onBeforeUnmount(() => {
  request?.abort()
  clearPreview()
})
</script>

<template>
  <SettingsDialogShell
    :title="mode === 'route' ? '只整理连线' : '智能排版'"
    width="min(1440px, calc(100vw - 48px))"
    height="calc(100dvh - 48px)"
    icon="ri-node-tree"
    @close="emit('close')"
  >
    <div class="tidy-dialog-body">
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
            <option value="smart">自动推荐</option>
            <option value="main">突出主流程</option>
            <option value="lanes">按角色或分区排版</option>
            <optgroup label="基础布局">
              <option value="auto">按方向均匀排布</option>
              <option value="right">横向流程</option>
              <option value="down">纵向流程</option>
              <option value="tree">树形</option>
            </optgroup>
          </select>
        </label>
      </div>
      <p v-if="busy" role="status">正在生成整理预览…</p>
      <p v-else-if="error" role="alert">{{ error }}</p>
      <template v-else-if="preview">
        <p role="status">
          <template v-if="preview.sections">重排 {{ preview.sections }} 个分区，调整 {{ preview.images }} 张图片和 {{ preview.captions }} 处文字。</template>
          <template v-else-if="preview.laneCount">按 {{ preview.laneCount }} 个角色泳道呈现流程交接。</template>
          <template v-else-if="preview.changed && preview.mainCount">将 {{ preview.mainCount }} 个主流程节点连成阅读主轴。</template>
          <template v-else-if="preview.changed">移动 {{ preview.moved }} 个图形，重排 {{ preview.routed }} 条连线。</template>
          <template v-else-if="preview.limited === 'lanes'">需要现有截图分区，或至少两个相连的角色。可以在节点文字首行写「角色：名称」。</template>
          <template v-else-if="preview.limited === 'wiring'">当前方案可能遮挡节点或增加交叉。可以缩小选区或切换布局。</template>
          <template v-else-if="preview.limited === 'space'">画框空间不足或有固定内容占位。可扩大画框或缩小整理范围。</template>
          <template v-else>当前没有可安全改善的布局。</template>
          <span v-if="preview.crossings && preview.crossings.after < preview.crossings.before">
            连线交叉从 {{ preview.crossings.before }} 处减少到 {{ preview.crossings.after }} 处。
          </span>
          <span v-if="preview.changed && preview.skipped">保留了 {{ preview.skipped }} 项受画框、固定内容或连线约束的对象。</span>
        </p>
        <WaoButton v-if="preview.canExpandFrames || expandFrames" variant="ghost"
          :aria-pressed="expandFrames" @click="expandFrames = !expandFrames">
          {{ expandFrames ? '按原画框预览' : '尝试扩展画框' }}
        </WaoButton>
        <template v-if="preview.changed">
          <div class="tidy-settings" aria-label="预览操作">
            <WaoButton variant="ghost" :aria-pressed="view === 'after'" @click="view = 'after'">整理后</WaoButton>
            <WaoButton variant="ghost" :aria-pressed="view === 'before'" @click="view = 'before'">整理前</WaoButton>
            <WaoButton variant="ghost" :aria-pressed="view === 'compare'" @click="view = 'compare'">并排对照</WaoButton>
            <WaoButton variant="ghost" aria-label="缩小预览" :disabled="zoom <= 1" @click="zoom /= 2">−</WaoButton>
            <WaoButton variant="ghost" aria-label="放大预览" :disabled="zoom >= 4" @click="zoom *= 2">＋</WaoButton>
          </div>
          <div class="tidy-previews">
            <figure v-if="view !== 'after'"><figcaption v-if="view === 'compare'">整理前</figcaption>
              <div class="tidy-preview-scroll" tabindex="0" aria-label="整理前预览">
                <img :src="beforeUrl" alt="整理前的草图" :class="{ 'fit-preview': zoom === 1 }"
                  :style="zoom > 1 ? { width: `${zoom * 100}%` } : undefined" />
              </div>
            </figure>
            <figure v-if="view !== 'before'"><figcaption v-if="view === 'compare'">整理后</figcaption>
              <div class="tidy-preview-scroll" tabindex="0" aria-label="整理后预览">
                <img :src="afterUrl" alt="整理后的草图" :class="{ 'fit-preview': zoom === 1 }"
                  :style="zoom > 1 ? { width: `${zoom * 100}%` } : undefined" />
              </div>
            </figure>
          </div>
        </template>
      </template>
    </div>
    <template #footer>
      <WaoButton variant="ghost" @click="emit('close')">取消</WaoButton>
      <WaoButton v-if="error" @click="generate()">重新预览</WaoButton>
      <WaoButton variant="primary" :disabled="busy || disabled || !preview?.changed" @click="apply">应用整理</WaoButton>
    </template>
  </SettingsDialogShell>
</template>

<style scoped>
.tidy-dialog-body {
  padding: var(--wao-space-2) var(--wao-space-3);
}
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
.tidy-preview-scroll {
  overflow: auto;
  max-height: 62dvh;
}
.tidy-preview-scroll img { display: block; max-width: none; }
.tidy-preview-scroll .fit-preview {
  max-width: 100%;
  max-height: 44dvh;
  margin-inline: auto;
  object-fit: contain;
}
.tidy-settings button[aria-pressed='true'] { background: var(--wao-primary-soft); }
@media (max-width: 560px) {
  .tidy-dialog-body { padding-inline: var(--wao-space-2); }
  .tidy-settings { gap: var(--wao-space-1); }
}
@media (pointer: coarse) {
  .tidy-settings select { min-height: 44px; }
}
</style>
