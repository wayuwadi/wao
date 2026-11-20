<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import { useTheme } from '@/composables/theme'
import { fetchDrawingPhoto, startDrawingPhoto } from '@/api/drawings'
import { uploadReferenceImage } from '@/api/referenceImages'
import { createDrawingPhotoScene, parseDrawingPhotoPlan } from '@/utils/drawingPhotoScene'

const props = defineProps<{
  owner: string
  saveDrawing: (title: string, scene: string, requestId: string) => Promise<void>
  downloadDrawing: (title: string, scene: string) => void
}>()
const emit = defineEmits<{ close: [] }>()
const { effective } = useTheme()
type Draft = { referenceId: string; requestId: string; runId?: string }
const key = `wao:drawing-photo:${props.owner}`
const newRequestId = () => crypto.randomUUID().replace(/-/g, '')
const requests = new AbortController()
const file = shallowRef<File>()
const photoUrl = ref('')
const previewUrl = ref('')
const title = ref('')
const scene = ref('')
const status = ref('')
const error = ref('')
const uncertainties = ref<string[]>([])
const zoomed = ref(false)
const working = ref(false)
const saving = ref(false)
const restart = ref(false)
const uploadInput = ref<HTMLInputElement>()
const draft = ref<Draft>()

const canRecognize = computed(() => !working.value && !saving.value && !!(file.value || draft.value))
const hasPreview = computed(() => !!scene.value && !!previewUrl.value)
const sourceUrl = computed(() => photoUrl.value ||
  (draft.value ? `/api/reference-images/${draft.value.referenceId}/raw` : ''))

function remember(value?: Draft) {
  draft.value = value
  try {
    if (value) sessionStorage.setItem(key, JSON.stringify(value))
    else sessionStorage.removeItem(key)
  } catch { /* 识别仍可在当前弹窗继续。 */ }
}

function restoreJob() {
  try {
    const value = JSON.parse(sessionStorage.getItem(key) ?? 'null') as Draft | null
    if (value && /^[a-f0-9]{32}$/.test(value.referenceId) && /^[a-f0-9]{32}$/.test(value.requestId) &&
        (!value.runId || /^ac[a-f0-9]{24}$/.test(value.runId))) {
      draft.value = value
      void recognize()
    }
  } catch { /* 已失效的草稿不会影响选择新照片。 */ }
}

function clearPreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  scene.value = ''
  uncertainties.value = []
  zoomed.value = false
}

function selectPhoto(event: Event) {
  const input = event.target as HTMLInputElement
  const selected = input.files?.[0]
  if (!selected || working.value || saving.value) return
  if (!/\.(?:jpe?g|png|webp)$/i.test(selected.name) || !selected.size || selected.size > 10 * 1024 * 1024) {
    error.value = '请选择 10 MB 以内的 JPG、PNG 或 WebP 照片。'
    input.value = ''
    return
  }
  remember()
  clearPreview()
  restart.value = false
  status.value = ''
  error.value = ''
  file.value = selected
  input.value = ''
  if (photoUrl.value) URL.revokeObjectURL(photoUrl.value)
  photoUrl.value = URL.createObjectURL(selected)
}

async function recognize() {
  if (!canRecognize.value) return
  const { signal } = requests
  working.value = true
  error.value = ''
  let failedResult = false
  try {
    let current = draft.value
    if (!current && file.value) {
      status.value = '正在上传照片…'
      const image = await uploadReferenceImage(file.value, signal)
      signal.throwIfAborted()
      current = { referenceId: image.id, requestId: newRequestId() }
      remember(current)
    }
    if (!current) return
    if (restart.value) {
      current = { referenceId: current.referenceId, requestId: newRequestId() }
      remember(current)
      restart.value = false
    }
    status.value = '正在读取识别任务…'
    let job = current.runId
      ? await fetchDrawingPhoto(current.runId, signal)
      : await startDrawingPhoto(current.referenceId, current.requestId, signal)
    signal.throwIfAborted()
    remember({ ...current, runId: job.id })
    while (job.status === 'queued' || job.status === 'running') {
      status.value = job.status === 'queued' ? '等待本机 Codex 处理…' : '正在识别框、文字和连线…'
      await new Promise((resolve) => window.setTimeout(resolve, 2000))
      signal.throwIfAborted()
      job = await fetchDrawingPhoto(job.id, signal)
    }
    signal.throwIfAborted()
    if (job.status !== 'succeeded' || !job.result) {
      failedResult = true
      throw new Error(job.message || '识别未完成，请重试。')
    }
    status.value = '正在生成预览…'
    failedResult = true
    const plan = parseDrawingPhotoPlan(job.result)
    const nextScene = await createDrawingPhotoScene(plan)
    failedResult = false
    const { renderDrawingImage } = await import('@/utils/drawingImageExport')
    const blob = await renderDrawingImage(nextScene, 'png', 3400)
    signal.throwIfAborted()
    clearPreview()
    scene.value = nextScene
    previewUrl.value = URL.createObjectURL(blob)
    title.value = plan.title
    uncertainties.value = plan.uncertainties
  } catch (cause) {
    if (signal.aborted) return
    restart.value = failedResult
    error.value = cause instanceof Error ? cause.message : '无法生成预览，请重试。'
  } finally {
    working.value = false
    status.value = ''
  }
}

async function save() {
  const current = draft.value
  if (!hasPreview.value || !title.value.trim() || !current || saving.value) return
  saving.value = true
  error.value = ''
  try {
    await props.saveDrawing(title.value.trim(), scene.value, current.requestId)
    remember()
    emit('close')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存失败，可重试或下载草图。'
  } finally {
    saving.value = false
  }
}

onMounted(restoreJob)
onBeforeUnmount(() => {
  requests.abort()
  if (photoUrl.value) URL.revokeObjectURL(photoUrl.value)
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})
</script>

<template>
  <SettingsDialogShell
    title="拍照转草图"
    icon="ri-camera-line"
    :width="hasPreview ? '1120px' : '640px'"
    :theme="effective"
    height="auto"
    :close-disabled="saving"
    @close="emit('close')"
  >
    <div class="photo-body">
      <div v-if="!hasPreview" class="choose">
        <p>上传白板或手绘图，识别后生成可编辑的框、文字和连线。上传的照片会保存在你的参考图中。</p>
        <WaoButton variant="secondary" :disabled="working || saving" @click="uploadInput?.click()">
          <i class="ri-image-add-line" aria-hidden="true"></i>
          {{ file || draft ? '换一张照片' : '选择照片' }}
        </WaoButton>
        <p>JPG、PNG 或 WebP，最大 10 MB</p>
      </div>
      <input ref="uploadInput" type="file" accept=".jpg,.jpeg,.png,.webp" hidden @change="selectPhoto" />
      <div v-if="hasPreview" class="preview-controls">
        <label>草图名称 <input v-model="title" maxlength="128" :disabled="saving" /></label>
        <WaoButton variant="secondary" @click="zoomed = !zoomed">
          {{ zoomed ? '适应窗口' : '放大查看' }}
        </WaoButton>
      </div>
      <div v-if="sourceUrl || previewUrl" class="previews" :class="{ 'has-both': sourceUrl && previewUrl }">
        <figure v-if="sourceUrl">
          <figcaption>原照片</figcaption>
          <div class="preview-frame"><img :src="sourceUrl" alt="原始白板或手绘图" /></div>
        </figure>
        <figure v-if="previewUrl">
          <figcaption>草图预览{{ uncertainties.length ? ` · ${uncertainties.length} 处待核对` : '' }}</figcaption>
          <div class="preview-frame" :class="{ zoomed }" tabindex="0"><img :src="previewUrl" alt="识别生成的可编辑草图预览" /></div>
        </figure>
      </div>
      <details v-if="uncertainties.length">
        <summary>查看待核对内容</summary>
        <ul><li v-for="(item, index) in uncertainties" :key="index">{{ item }}</li></ul>
      </details>
      <p v-if="status" role="status">{{ status }}</p>
      <p v-if="error" class="photo-error" role="alert">{{ error }}</p>
    </div>
    <template #footer>
      <div class="photo-actions">
        <template v-if="hasPreview">
          <WaoButton variant="secondary" :disabled="saving" @click="uploadInput?.click()">换一张照片</WaoButton>
          <WaoButton variant="secondary" :disabled="saving" @click="downloadDrawing(title.trim(), scene)">下载草图</WaoButton>
          <WaoButton variant="primary" :loading="saving" :disabled="!title.trim()" @click="save">保存为新草图</WaoButton>
        </template>
        <template v-else>
          <WaoButton variant="secondary" @click="emit('close')">{{ working && draft ? '稍后查看' : '关闭' }}</WaoButton>
          <WaoButton variant="primary" :disabled="!canRecognize" :loading="working" @click="recognize">
            {{ restart ? '重新识别' : draft ? '继续识别' : '识别并预览' }}
          </WaoButton>
        </template>
      </div>
    </template>
  </SettingsDialogShell>
</template>

<style scoped>
.photo-body { display: grid; gap: var(--wao-space-2); padding: var(--wao-space-3); }
.photo-body p { margin: 0; color: var(--wao-text-2); }
.photo-body .photo-error { color: var(--wao-danger); }
.choose { display: grid; justify-items: start; gap: var(--wao-space-2); }
.preview-controls, .photo-actions { display: flex; align-items: center; gap: var(--wao-space-1); flex-wrap: wrap; }
.preview-controls label { display: flex; align-items: center; gap: var(--wao-space-1); flex: 1; }
.preview-controls input {
  min-width: 0;
  flex: 1;
  min-height: 40px;
  padding: 0 var(--wao-space-1);
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font: inherit;
}
.photo-actions { justify-content: flex-end; }
.previews { display: grid; gap: var(--wao-space-2); min-width: 0; }
.previews.has-both { grid-template-columns: minmax(0, 1fr) minmax(0, 1.6fr); }
figure { margin: 0; min-width: 0; }
figcaption { margin-bottom: var(--wao-space-1); color: var(--wao-text-2); }
.preview-frame {
  overflow: auto;
  max-height: min(48dvh, 540px);
  border: 1px solid var(--wao-divider);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-sub);
}
img { display: block; width: 100%; max-height: min(48dvh, 540px); object-fit: contain; }
.zoomed img { width: auto; max-height: none; }
summary { cursor: pointer; }
ul { margin-bottom: 0; }
@media (max-width: 650px) {
  .previews.has-both { grid-template-columns: minmax(0, 1fr); }
}
</style>
