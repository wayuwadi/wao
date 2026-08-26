<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  fetchSharedChatMessages,
  retrySharedChatFeishu,
  sendSharedChatMessage,
  uploadSharedChatImage,
  type SharedChatMessage,
} from '@/api/chat'
import { useSessionStore } from '@/stores/session'

const POLL_MS = 5_000
const MAX_IMAGE_BYTES = 10 * 1024 * 1024
const session = useSessionStore()
const messages = ref<SharedChatMessage[]>([])
const draft = ref('')
const selectedImage = ref<File | null>(null)
const previewUrl = ref('')
const uploadedImageId = ref('')
const uploadedImageName = ref('')
const uploadedImageUrl = ref('')
const clientToken = ref('')
const loading = ref(true)
const loadError = ref('')
const sending = ref(false)
const uploadingImage = ref(false)
const sendError = ref('')
const retryingId = ref('')
const stream = ref<HTMLElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let alive = true

const storageKey = computed(() => `wao-shared-chat-draft-v1:${session.userId || 'unknown'}`)
const contentLength = computed(() => Array.from(draft.value).length)
const canSend = computed(
  () =>
    !sending.value &&
    !uploadingImage.value &&
    contentLength.value <= 2_000 &&
    (!!draft.value.trim() || !!selectedImage.value || !!uploadedImageId.value),
)

function messageOf(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}

function newToken() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID().replace(/-/g, '')
  }
  return `chat${Date.now()}${Math.random().toString(36).slice(2)}`
}

function formatTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('zh-CN', {
        timeZone: 'Asia/Shanghai',
        month: 'numeric',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
        hour12: false,
      })
}

function nearBottom() {
  const el = stream.value
  return !el || el.scrollHeight - el.scrollTop - el.clientHeight < 96
}

async function scrollBottom() {
  await nextTick()
  if (stream.value) stream.value.scrollTop = stream.value.scrollHeight
}

function merge(incoming: SharedChatMessage[]) {
  if (!incoming.length) return
  const byId = new Map(messages.value.map((message) => [message.id, message]))
  incoming.forEach((message) => byId.set(message.id, message))
  messages.value = [...byId.values()].sort(
    (left, right) =>
      new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime() ||
      left.id.localeCompare(right.id),
  )
}

async function load(initial = false) {
  const shouldFollow = initial || nearBottom()
  try {
    const after = initial ? undefined : messages.value[messages.value.length - 1]?.id
    merge(await fetchSharedChatMessages(after))
    loadError.value = ''
    if (shouldFollow) await scrollBottom()
  } catch (error) {
    loadError.value = messageOf(error, '聊天记录读取失败，请重试')
  } finally {
    if (initial) loading.value = false
  }
}

async function poll() {
  if (!alive) return
  if (document.visibilityState === 'visible' && !sending.value) await load(false)
  if (alive) pollTimer = setTimeout(poll, POLL_MS)
}

function clearImage() {
  selectedImage.value = null
  uploadedImageId.value = ''
  uploadedImageName.value = ''
  uploadedImageUrl.value = ''
  if (previewUrl.value.startsWith('blob:')) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

async function uploadSelectedImage() {
  if (!selectedImage.value || uploadedImageId.value) return
  uploadingImage.value = true
  try {
    const uploaded = await uploadSharedChatImage(selectedImage.value)
    uploadedImageId.value = uploaded.id ?? ''
    if (!uploadedImageId.value) throw new Error('截图未能持久化，请重试')
    uploadedImageName.value = uploaded.name
    uploadedImageUrl.value = uploaded.url
  } catch (error) {
    sendError.value = messageOf(error, '截图上传失败，文件仍已保留，请重试')
  } finally {
    uploadingImage.value = false
  }
}

function selectImage(file?: File) {
  sendError.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) {
    sendError.value = '只能发送图片文件'
    return
  }
  if (file.size > MAX_IMAGE_BYTES) {
    sendError.value = '截图超过 10MB，请压缩后重试'
    return
  }
  clearImage()
  selectedImage.value = file
  previewUrl.value = URL.createObjectURL(file)
  void uploadSelectedImage()
}

function onPaste(event: ClipboardEvent) {
  const file = [...(event.clipboardData?.files ?? [])].find((item) =>
    item.type.startsWith('image/'),
  )
  if (file) selectImage(file)
}

function onDrop(event: DragEvent) {
  selectImage(event.dataTransfer?.files?.[0])
}

async function send() {
  if (!canSend.value) return
  sending.value = true
  sendError.value = ''
  if (!clientToken.value) clientToken.value = newToken()
  try {
    if (selectedImage.value && !uploadedImageId.value) {
      await uploadSelectedImage()
      if (!uploadedImageId.value) throw new Error(sendError.value || '截图未能持久化，请重试')
    }
    const saved = await sendSharedChatMessage({
      clientToken: clientToken.value,
      content: draft.value,
      imageId: uploadedImageId.value || undefined,
    })
    merge([saved])
    draft.value = ''
    localStorage.removeItem(storageKey.value)
    clearImage()
    clientToken.value = ''
    await scrollBottom()
  } catch (error) {
    sendError.value = messageOf(error, '发送失败，草稿已保留，请重试')
  } finally {
    sending.value = false
  }
}

async function retryFeishu(message: SharedChatMessage) {
  retryingId.value = message.id
  try {
    merge([await retrySharedChatFeishu(message.id)])
  } catch (error) {
    sendError.value = messageOf(error, '飞书同步重试失败')
  } finally {
    retryingId.value = ''
  }
}

watch([draft, uploadedImageId, uploadedImageName, uploadedImageUrl], () => {
  if (draft.value || uploadedImageId.value) {
    localStorage.setItem(
      storageKey.value,
      JSON.stringify({
        content: draft.value,
        imageId: uploadedImageId.value,
        imageName: uploadedImageName.value,
        imageUrl: uploadedImageUrl.value,
      }),
    )
  } else localStorage.removeItem(storageKey.value)
})

onMounted(async () => {
  const stored = localStorage.getItem(storageKey.value)
  if (stored) {
    try {
      const parsed = JSON.parse(stored) as Partial<{
        content: string
        imageId: string
        imageName: string
        imageUrl: string
      }>
      draft.value = parsed.content ?? ''
      uploadedImageId.value = parsed.imageId ?? ''
      uploadedImageName.value = parsed.imageName ?? ''
      uploadedImageUrl.value = parsed.imageUrl ?? ''
      previewUrl.value = uploadedImageUrl.value
    } catch {
      // 兼容上一版只保存纯文字的本地草稿。
      draft.value = stored
    }
  }
  await load(true)
  pollTimer = setTimeout(poll, POLL_MS)
})

onBeforeUnmount(() => {
  alive = false
  if (pollTimer) clearTimeout(pollTimer)
  if (previewUrl.value.startsWith('blob:')) URL.revokeObjectURL(previewUrl.value)
})
</script>

<template>
  <main class="chat-page">
    <header class="chat-header">
      <div>
        <p class="eyebrow">公共空间</p>
        <h1>聊天</h1>
        <p>临时聊几句、贴一张电脑截图；消息会先保存在 WAO，再同步到系统默认飞书群。</p>
      </div>
      <RouterLink class="settings-link" to="/settings?tab=webhook">
        <i class="ri-settings-3-line"></i> Webhook配置
      </RouterLink>
    </header>

    <section class="chat-shell">
      <div ref="stream" class="message-stream" aria-live="polite">
        <div v-if="loading" class="state-card">
          <i class="ri-loader-4-line spin"></i> 正在读取聊天记录…
        </div>
        <div v-else-if="loadError && !messages.length" class="state-card error-state">
          <span>{{ loadError }}</span>
          <button type="button" @click="load(true)">重新读取</button>
        </div>
        <div v-else-if="!messages.length" class="state-card empty-state">
          <i class="ri-chat-smile-2-line"></i>
          <strong>还没有消息</strong>
          <span>从一句话或一张截图开始。</span>
        </div>

        <article
          v-for="message in messages"
          :key="message.id"
          class="message-row"
          :class="{ mine: message.author.id === session.userId }"
        >
          <span class="avatar" :style="{ background: message.author.color }">{{
            message.author.initial
          }}</span>
          <div class="message-body">
            <div class="message-meta">
              <strong>{{ message.author.name }}</strong>
              <time>{{ formatTime(message.createdAt) }}</time>
            </div>
            <p v-if="message.content" class="message-text">{{ message.content }}</p>
            <a
              v-if="message.imageUrl"
              class="message-image"
              :href="message.imageUrl"
              target="_blank"
              rel="noopener noreferrer"
              :aria-label="`打开截图 ${message.imageName || ''}`"
            >
              <img :src="message.imageUrl" :alt="message.imageName || '聊天截图'" />
            </a>
            <div class="delivery" :class="message.feishuStatus.toLowerCase()">
              <template v-if="message.feishuStatus === 'SENT'">
                <i class="ri-check-double-line"></i> 已同步飞书
              </template>
              <template v-else-if="message.feishuStatus === 'PENDING'">
                <i class="ri-loader-4-line spin"></i> 正在同步飞书
              </template>
              <template v-else>
                <i class="ri-alert-line"></i> {{ message.feishuError || '未同步飞书' }}
                <button
                  v-if="['FAILED', 'UNAVAILABLE'].includes(message.feishuStatus)"
                  type="button"
                  :disabled="retryingId === message.id"
                  @click="retryFeishu(message)"
                >
                  {{ retryingId === message.id ? '重试中…' : '重试' }}
                </button>
              </template>
            </div>
          </div>
        </article>
        <div v-if="loadError && messages.length" class="inline-error">
          {{ loadError }} <button type="button" @click="load(false)">重试</button>
        </div>
      </div>

      <form class="composer" @submit.prevent="send" @drop.prevent="onDrop" @dragover.prevent>
        <div v-if="previewUrl" class="image-preview">
          <img :src="previewUrl" alt="待发送截图预览" />
          <span>{{ selectedImage?.name || uploadedImageName }}</span>
          <button type="button" aria-label="移除截图" @click="clearImage">
            <i class="ri-close-line"></i>
          </button>
        </div>
        <textarea
          v-model="draft"
          rows="3"
          maxlength="2000"
          placeholder="输入消息，或直接粘贴截图…"
          aria-label="聊天消息"
          @paste="onPaste"
          @keydown.ctrl.enter.prevent="send"
          @keydown.meta.enter.prevent="send"
        ></textarea>
        <div class="composer-footer">
          <div class="composer-status">
            <span v-if="uploadingImage">正在保存截图草稿…</span>
            <span v-else-if="sendError" class="send-error">{{ sendError }}</span>
            <span v-else>{{ draft ? '草稿已保存在本机' : 'Ctrl / ⌘ + Enter 发送' }}</span>
            <span :class="{ over: contentLength > 2000 }">{{ contentLength }}/2000</span>
          </div>
          <div class="composer-actions">
            <input
              ref="fileInput"
              class="file-input"
              type="file"
              accept="image/*"
              @change="selectImage(($event.target as HTMLInputElement).files?.[0])"
            />
            <button type="button" class="image-button" @click="fileInput?.click()">
              <i class="ri-image-add-line"></i> 截图
            </button>
            <button class="send-button" type="submit" :disabled="!canSend">
              <i :class="sending ? 'ri-loader-4-line spin' : 'ri-send-plane-2-fill'"></i>
              {{ sending ? (uploadedImageId ? '发送中…' : '上传并发送…') : '发送' }}
            </button>
          </div>
        </div>
      </form>
    </section>
  </main>
</template>

<style scoped>
.chat-page {
  height: calc(100vh - 112px);
  min-height: 560px;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 24px;
  color: var(--text-primary, #202124);
}
.chat-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}
.eyebrow {
  margin: 0 0 4px;
  color: #6f61c4;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.12em;
}
h1 {
  margin: 0;
  font-size: 28px;
}
.chat-header p:last-child {
  margin: 7px 0 0;
  color: var(--text-secondary, #6d6f76);
}
.settings-link {
  flex: none;
  color: #6754bd;
  text-decoration: none;
  font-size: 13px;
}
.chat-shell {
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--border-color, #e6e3ef);
  border-radius: 18px;
  background: var(--surface, #fff);
  box-shadow: 0 12px 36px rgba(52, 42, 91, 0.08);
}
.message-stream {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  padding: 22px clamp(14px, 3vw, 34px);
  background: linear-gradient(180deg, rgba(247, 245, 252, 0.7), rgba(255, 255, 255, 0.2));
}
.message-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  max-width: 760px;
  margin: 0 auto 18px 0;
}
.message-row.mine {
  margin-right: 0;
  margin-left: auto;
  flex-direction: row-reverse;
}
.avatar {
  width: 34px;
  height: 34px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 11px;
  color: #fff;
  font-weight: 700;
}
.message-body {
  min-width: 130px;
  max-width: min(680px, calc(100vw - 150px));
  padding: 11px 13px 8px;
  border: 1px solid #e9e6ef;
  border-radius: 4px 16px 16px;
  background: #fff;
  box-shadow: 0 3px 12px rgba(53, 45, 80, 0.05);
}
.mine .message-body {
  border-radius: 16px 4px 16px 16px;
  background: #f0edff;
  border-color: #ddd6ff;
}
.message-meta {
  display: flex;
  align-items: baseline;
  gap: 9px;
  margin-bottom: 5px;
  font-size: 12px;
}
.message-meta time {
  color: #96929e;
}
.message-text {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.65;
}
.message-image {
  display: block;
  margin-top: 8px;
}
.message-image img {
  display: block;
  max-width: min(420px, 100%);
  max-height: 360px;
  border-radius: 10px;
  object-fit: contain;
  background: #eeeaf4;
}
.delivery {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-top: 7px;
  color: #8a8790;
  font-size: 11px;
}
.delivery.sent {
  color: #4e8b6c;
}
.delivery.failed,
.delivery.unavailable {
  color: #a96a46;
}
.delivery button,
.inline-error button,
.state-card button {
  border: 0;
  background: none;
  color: #6754bd;
  cursor: pointer;
  text-decoration: underline;
}
.state-card {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #7e7988;
}
.empty-state {
  flex-direction: column;
}
.empty-state i {
  font-size: 34px;
  color: #9386d2;
}
.inline-error {
  text-align: center;
  color: #a75d42;
  font-size: 12px;
}
.composer {
  padding: 14px 16px 16px;
  border-top: 1px solid var(--border-color, #e6e3ef);
  background: var(--surface, #fff);
}
.composer textarea {
  box-sizing: border-box;
  width: 100%;
  resize: none;
  border: 0;
  outline: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  line-height: 1.55;
}
.image-preview {
  display: flex;
  align-items: center;
  gap: 9px;
  width: fit-content;
  max-width: 100%;
  margin-bottom: 8px;
  padding: 6px 8px;
  border-radius: 10px;
  background: #f4f1fa;
  font-size: 12px;
}
.image-preview img {
  width: 52px;
  height: 42px;
  border-radius: 7px;
  object-fit: cover;
}
.image-preview span {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.image-preview button {
  border: 0;
  background: transparent;
  cursor: pointer;
}
.composer-footer,
.composer-actions,
.composer-status {
  display: flex;
  align-items: center;
}
.composer-footer {
  justify-content: space-between;
  gap: 12px;
}
.composer-status {
  gap: 12px;
  color: #96929e;
  font-size: 11px;
}
.composer-status .send-error,
.composer-status .over {
  color: #b44f4f;
}
.composer-actions {
  gap: 8px;
}
.file-input {
  display: none;
}
.image-button,
.send-button {
  min-height: 36px;
  border-radius: 10px;
  padding: 0 14px;
  cursor: pointer;
  font-weight: 600;
}
.image-button {
  border: 1px solid #ddd7ea;
  background: #fff;
  color: #655c72;
}
.send-button {
  border: 0;
  background: #6655be;
  color: #fff;
}
.send-button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.spin {
  display: inline-block;
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 680px) {
  .chat-page {
    height: calc(100dvh - 92px);
    min-height: 480px;
    padding: 12px;
    gap: 12px;
  }
  .chat-header {
    align-items: flex-start;
  }
  .chat-header p:last-child {
    font-size: 12px;
  }
  .settings-link {
    font-size: 0;
  }
  .settings-link i {
    font-size: 20px;
  }
  .message-body {
    max-width: calc(100vw - 100px);
  }
  .composer-footer {
    align-items: flex-end;
  }
  .composer-status {
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
  }
  .image-button {
    padding: 0 10px;
    font-size: 0;
  }
  .image-button i {
    font-size: 18px;
  }
}
</style>
