<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, useId } from 'vue'
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import {
  createDrawingShare,
  drawingShareUrl,
  listDrawingShares,
  revokeDrawingShare,
  type DrawingShareLink,
  type DrawingShareValidity,
} from '@/api/drawingShare'
import { copyToClipboard } from '@/utils/clipboard'

const props = defineProps<{ drawingId: string; title: string }>()
const emit = defineEmits<{ close: [] }>()
const entries = ref<DrawingShareLink[]>([])
const shownAddress = ref<DrawingShareLink>()
const validity = ref<DrawingShareValidity>('7d')
const validityId = useId()
const loading = ref(false)
const loaded = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const request = new AbortController()

async function load() {
  loading.value = true
  error.value = ''
  try {
    entries.value = await listDrawingShares(props.drawingId, request.signal)
    loaded.value = true
  } catch {
    if (!request.signal.aborted) error.value = '无法读取分享链接，请重试'
  } finally {
    loading.value = false
  }
}

async function create() {
  if (busy.value || !loaded.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const link = await createDrawingShare(props.drawingId, validity.value)
    if (request.signal.aborted) return
    shownAddress.value = link
    entries.value = [link, ...entries.value]
    notice.value = '链接已生成，可以复制发送'
  } catch {
    if (!request.signal.aborted) error.value = '生成链接失败，请重试'
  } finally {
    busy.value = false
  }
}

async function revoke(entry: DrawingShareLink) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await revokeDrawingShare(props.drawingId, entry.id)
    entries.value = entries.value.filter(({ id }) => id !== entry.id)
    if (shownAddress.value?.id === entry.id) shownAddress.value = undefined
    notice.value = '链接已撤销'
  } catch {
    if (!request.signal.aborted) error.value = '撤销失败，请重试'
  } finally {
    busy.value = false
  }
}

async function copy(entry: DrawingShareLink) {
  if (!entry.token) return
  const copied = await copyToClipboard(drawingShareUrl(entry.token))
  if (request.signal.aborted || !entries.value.some(({ id }) => id === entry.id)) return
  if (!copied) shownAddress.value = entry
  notice.value = copied ? '链接已复制' : '复制失败，请手动复制地址'
}

const formatTime = (value: string) => new Date(value).toLocaleString()
onMounted(load)
onBeforeUnmount(() => request.abort())
</script>

<template>
  <SettingsDialogShell
    title="分享草图"
    :description="title"
    width="640px"
    height="auto"
    :close-disabled="busy"
    @close="emit('close')"
  >
    <div class="drawing-share-settings">
      <p>持有链接的人无需登录即可查看最新保存的画布，链接可以转发。</p>
      <p v-if="loading" role="status">正在读取分享链接…</p>
      <template v-else-if="loaded">
        <form class="share-fields" @submit.prevent="create">
          <div class="share-choice">
            <label :for="validityId">有效期</label>
            <select :id="validityId" v-model="validity" :disabled="busy">
              <option value="1h">1 小时</option>
              <option value="1d">1 天</option>
              <option value="7d">7 天（默认）</option>
              <option value="permanent">长期有效，直到撤销</option>
            </select>
          </div>
          <WaoButton type="submit" variant="primary" :disabled="busy || entries.length >= 20">
            生成只读链接
          </WaoButton>
        </form>
        <p v-if="entries.length >= 20">最多可有 20 个有效链接。撤销一个后可以继续生成。</p>
        <section v-if="shownAddress?.token" class="share-fields" aria-label="分享链接地址">
          <label class="share-url">
            分享地址
            <input :value="drawingShareUrl(shownAddress.token)" readonly @focus="($event.target as HTMLInputElement).select()" />
          </label>
        </section>
        <section aria-label="有效链接" class="share-links">
          <h3>有效链接</h3>
          <p v-if="!entries.length">还没有分享链接。</p>
          <ul v-if="entries.length">
            <li v-for="entry in entries" :key="entry.id">
              <div>
                <div>生成于 {{ formatTime(entry.createdAt) }}</div>
                <div>{{ entry.expiresAt ? `到期于 ${formatTime(entry.expiresAt)}` : '长期有效' }}</div>
                <div v-if="!entry.token">无法从此处复制原链接</div>
              </div>
              <div class="share-link-actions">
                <WaoButton v-if="entry.token" :disabled="busy" @click="copy(entry)">复制链接</WaoButton>
                <WaoButton variant="danger" :disabled="busy" @click="revoke(entry)">撤销链接</WaoButton>
              </div>
            </li>
          </ul>
        </section>
      </template>
      <p v-if="error" class="share-error" role="alert">
        {{ error }}
        <WaoButton v-if="!loaded && !loading" @click="load">重试</WaoButton>
      </p>
      <p v-if="notice" role="status">{{ notice }}</p>
    </div>
    <template #footer><WaoButton :disabled="busy" @click="emit('close')">关闭</WaoButton></template>
  </SettingsDialogShell>
</template>

<style scoped>
.drawing-share-settings { display: grid; gap: var(--wao-space-2); padding: var(--wao-space-3); }
p, h3, ul { margin: 0; }
p, li { color: var(--wao-text-3); font-size: var(--wao-font-help); }
h3 { font-size: var(--wao-font-section); }
ul { list-style: none; padding: 0; }
.share-fields, li { display: flex; flex-wrap: wrap; align-items: end; gap: var(--wao-space-1); }
.share-choice, .share-url { display: grid; gap: var(--wao-space-1); }
.share-url { flex: 1 1 16rem; min-width: 0; }
select, input {
  min-width: 0;
  min-height: 44px;
  padding: 0 var(--wao-space-1);
  font: inherit;
  color: var(--wao-text);
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line-control);
  border-radius: var(--wao-radius-control);
}
.share-links { display: grid; gap: var(--wao-space-1); }
.share-link-actions { display: flex; flex-wrap: wrap; gap: var(--wao-space-1); }
li { align-items: center; justify-content: space-between; padding: var(--wao-space-1) 0; border-top: 1px solid var(--wao-line); }
.share-error { color: var(--wao-danger-strong); }
@media (pointer: coarse) { .drawing-share-settings button { min-height: 44px; } }
</style>
