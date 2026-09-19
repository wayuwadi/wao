<script setup lang="ts">
import {
  computed,
  defineAsyncComponent,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue'
import { useRouter } from 'vue-router'
import {
  fetchSharedChatMessages,
  editSharedChatMessage,
  markSharedChatRead,
  sendSharedChatMessage,
  toggleSharedChatReaction,
  uploadSharedChatImage,
  uploadSharedChatFile,
  type SharedChatMessage,
} from '@/api/chat'
import { useSpeechInput } from '@/composables/speechInput'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import { useDataStore } from '@/stores/data'
import { docShareTarget, taskShareTarget } from '@/utils/share'
import { fetchDoc } from '@/api/board'
import { toast } from '@/composables/toast'
import { renderMarkdown } from '@/utils/markdown'
import { createVisibilityPoller } from '@/composables/visibilityPolling'
import { hasDesktopToolbar } from '@/utils/desktopToolbar'

async function openSharedLink(event: MouseEvent) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  const link = event.target instanceof Element ? event.target.closest('a[href]') : null
  const href = link?.getAttribute('href') || ''
  const target = link ? taskShareTarget(href) : null
  const documentTarget = link ? docShareTarget(href) : null
  if (!target && !documentTarget) return
  event.preventDefault()
  if (target) {
    ui.closeChat()
    ui.openTask(target.taskId, target.subId)
    return
  }
  let doc = data.docById(documentTarget!.docId)
  if (!doc?.kbId) {
    try {
      doc = await fetchDoc(documentTarget!.docId)
      data.docs[doc.id] = doc
    } catch {
      ui.closeChat()
      await router.push(`/s/d/${encodeURIComponent(documentTarget!.docId)}`)
      return
    }
  }
  if (!doc?.kbId) {
    toast('小记或文档无法读取，请确认仍有访问权限后重试', 'ri-error-warning-line')
    return
  }
  ui.closeChat()
  await router.push({ path: `/kb/${doc.kbId}`, query: { doc: doc.id } })
}
const editingId = ref('')
const editDraft = ref('')
const editOriginal = ref('')
const editSaving = ref(false)
const editError = ref('')
const editKey = (id: string) => `wao-chat-edit:${session.userId}:${id}`
function startEdit(message: SharedChatMessage) {
  if (editSaving.value) return
  editingId.value = message.id
  editDraft.value = message.content
  editOriginal.value = message.content
  editError.value = ''
  try {
    const cached = JSON.parse(localStorage.getItem(editKey(message.id)) || 'null')
    if (typeof cached?.content === 'string' && typeof cached?.original === 'string') {
      editDraft.value = cached.content
      editOriginal.value = cached.original
    }
  } catch {
    editError.value = '本机草稿无法读取，请保持页面打开'
  }
}
watch([editDraft, editOriginal], () => {
  if (!editingId.value) return
  try {
    localStorage.setItem(
      editKey(editingId.value),
      JSON.stringify({ content: editDraft.value, original: editOriginal.value }),
    )
  } catch {
    editError.value = '本机草稿保存失败，请保持页面打开并保存修改'
  }
})
async function saveEdit() {
  if (!editingId.value || editSaving.value) return
  const id = editingId.value
  const member = session.userId
  editSaving.value = true
  editError.value = ''
  try {
    const saved = await editSharedChatMessage(id, editDraft.value, editOriginal.value)
    if (session.userId !== member) return
    merge([saved])
    try {
      localStorage.removeItem(editKey(id))
    } catch {
      /* 服务端已确认保存。 */
    }
    editingId.value = ''
    toast('消息修改已保存', 'ri-check-line')
  } catch (error) {
    editError.value = messageOf(error, '修改失败，草稿已保留，可重试')
  } finally {
    editSaving.value = false
  }
}

function acceptLatestMessage(content: string) {
  editOriginal.value = content
  editError.value = ''
}
const imageMenu = ref<{ x: number; y: number; image: HTMLImageElement } | null>(null)
function imageContext(event: MouseEvent) {
  if (!(event.target instanceof HTMLImageElement)) return
  event.preventDefault()
  imageMenu.value = {
    image: event.target,
    x: Math.max(8, Math.min(event.clientX, innerWidth - 160)),
    y: Math.max(8, Math.min(event.clientY, innerHeight - 56)),
  }
}
async function copyImage() {
  const img = imageMenu.value?.image
  imageMenu.value = null
  if (!img) return
  try {
    const canvas = document.createElement('canvas')
    canvas.width = img.naturalWidth
    canvas.height = img.naturalHeight
    const context = canvas.getContext('2d')
    if (!context || !canvas.width || !canvas.height) throw new Error('图片尚未加载')
    context.drawImage(img, 0, 0)
    const png = new Promise<Blob>((resolve, reject) =>
      canvas.toBlob(
        (blob) => (blob ? resolve(blob) : reject(new Error('图片转换失败'))),
        'image/png',
      ),
    )
    await navigator.clipboard.write([new ClipboardItem({ 'image/png': png })])
    toast('图片已复制', 'ri-file-copy-line')
  } catch {
    toast('图片复制失败，请打开图片后使用系统菜单复制或保存', 'ri-error-warning-line')
  }
}

const props = withDefaults(
  defineProps<{
    floating?: boolean
    visible?: boolean
    reviewId?: string
    reviewRequestVersion?: number
    draftScope?: string
  }>(),
  { floating: false, visible: true, reviewId: '', reviewRequestVersion: 0, draftScope: '' },
)
const emit = defineEmits<{
  minimize: []
  'unread-change': [count: number]
}>()

const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const ChatReviewPanel = defineAsyncComponent(() => import('@/components/chat/ChatReviewPanel.vue'))
const POLL_MS = 5_000
const MAX_IMAGE_BYTES = 10 * 1024 * 1024
const EMOJIS = ['😀', '😂', '😊', '🥰', '👍', '👏', '🙏', '🎉', '✅', '❤️', '🔥', '🤔']
const REACTIONS = [
  { type: 'THUMBSUP', emoji: '👍' },
  { type: 'HEART', emoji: '❤️' },
  { type: 'SMILE', emoji: '😊' },
  { type: 'DONE', emoji: '✅' },
  { type: 'APPLAUSE', emoji: '👏' },
  { type: 'FIRE', emoji: '🔥' },
  { type: 'PARTY', emoji: '🎉' },
  { type: 'THANKS', emoji: '🙏' },
  { type: 'LAUGH', emoji: '😂' },
  { type: 'WOW', emoji: '😮' },
  { type: 'THINKING', emoji: '🤔' },
  { type: 'SAD', emoji: '😢' },
  { type: 'LOVE', emoji: '😍' },
  { type: 'STRONG', emoji: '💪' },
  { type: 'OK', emoji: '👌' },
  { type: 'EYES', emoji: '👀' },
  { type: 'ROCKET', emoji: '🚀' },
  { type: 'HUNDRED', emoji: '💯' },
  { type: 'COFFEE', emoji: '☕' },
  { type: 'STAR', emoji: '⭐' },
]

const session = useSessionStore()
const ui = useUiStore()
const data = useDataStore()
const router = useRouter()
const messages = ref<SharedChatMessage[]>([])
let disposed = false
const draft = ref('')
const composer = ref<InstanceType<typeof MarkdownEditor> | null>(null)
let sendAttempt: { content: string; replyToId?: string; token: string } | null = null
const replyingTo = ref<SharedChatMessage | null>(null)
const loading = ref(true)
const loadError = ref('')
const sending = ref(false)
const uploadingImage = ref(false)
const uploadingFile = ref(false)
const attachmentInput = ref<HTMLInputElement | null>(null)
function appendDraft(text: string) {
  if (!composer.value?.appendMarkdown?.(text)) draft.value += text
}
const speech = useSpeechInput(appendDraft)
watch(
  () => props.visible,
  (visible) => {
    if (!visible) speech.stop()
  },
)
async function uploadAttachment(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || uploadingFile.value) return
  sendError.value = ''
  if (file.size > 50 * 1024 * 1024) {
    sendError.value = '文件超过 50MB，请压缩后重试'
    input.value = ''
    return
  }
  uploadingFile.value = true
  try {
    const uploaded = await uploadSharedChatFile(file)
    if (!uploaded.id) throw new Error('文件未保存，请重试')
    const name = file.name.replace(/[\\[\]<>]/g, '_').replace(/[\r\n]/g, ' ')
    appendDraft(`\n[${name}](/api/files/${encodeURIComponent(uploaded.id)}/download)\n`)
  } catch (error) {
    sendError.value = messageOf(error, '文件上传失败，请重新选择文件重试；文字草稿仍保留')
  } finally {
    uploadingFile.value = false
    input.value = ''
  }
}
const sendError = ref('')
const reactingId = ref('')
const reactionMenuId = ref('')
const emojiOpen = ref(false)
const unreadBoundaryId = ref('')
const stream = ref<HTMLElement | null>(null)
const atLatest = ref(true)
const positionReady = ref(false)
let streamResize: ResizeObserver | undefined
const reviewOpen = ref(false)
const initialReviewId = ref('')
const storageKey = computed(
  () =>
    `wao-shared-chat-draft-v2${props.draftScope ? `-${props.draftScope}` : ''}:${session.userId || 'unknown'}`,
)
const contentLength = computed(() => Array.from(draft.value).length)
const canSend = computed(
  () =>
    !sending.value &&
    !uploadingImage.value &&
    !uploadingFile.value &&
    !speech.listening.value &&
    contentLength.value <= 2_000 &&
    !!draft.value.trim(),
)
const unreadCount = computed(() => messages.value.filter((message) => !message.read).length)

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
  return !el || el.scrollHeight - el.scrollTop - el.clientHeight < 120
}

function isAtLatest() {
  const el = stream.value
  return !!el && el.clientHeight > 0 && el.scrollHeight - el.scrollTop - el.clientHeight <= 2
}

async function scrollBottom() {
  await nextTick()
  if (stream.value) stream.value.scrollTop = stream.value.scrollHeight
  atLatest.value = isAtLatest()
}

function onStreamScroll() {
  atLatest.value = isAtLatest()
  void markVisibleRead()
}

async function goToLatest() {
  await scrollBottom()
  await markVisibleRead()
}

async function onMediaLoaded() {
  if (props.visible && atLatest.value) await goToLatest()
}

function merge(incoming: SharedChatMessage[]) {
  const byId = new Map(messages.value.map((message) => [message.id, message]))
  incoming.forEach((message) => byId.set(message.id, message))
  messages.value = [...byId.values()].sort(
    (left, right) =>
      new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime() ||
      left.id.localeCompare(right.id),
  )
}

async function markVisibleRead() {
  if (
    !props.visible ||
    !positionReady.value ||
    document.visibilityState !== 'visible' ||
    !isAtLatest()
  )
    return
  const last = messages.value[messages.value.length - 1]
  if (!last || messages.value.every((message) => message.read)) return
  try {
    const readIds = new Set(messages.value.map((message) => message.id))
    await markSharedChatRead(last.id)
    messages.value = messages.value.map((message) =>
      readIds.has(message.id) ? { ...message, read: true } : message,
    )
  } catch (error) {
    loadError.value = messageOf(error, '已读状态同步失败，请重试')
  }
}

async function load(initial = false) {
  const memberId = session.userId
  const shouldFollow = props.visible && (initial || nearBottom())
  try {
    const incoming = await fetchSharedChatMessages()
    if (disposed || memberId !== session.userId) return
    if (initial && !unreadBoundaryId.value) {
      unreadBoundaryId.value = incoming.find((message) => !message.read)?.id ?? ''
    }
    // Recheck after the request: the user may have scrolled up while it was in flight.
    const follow = shouldFollow && (initial || isAtLatest())
    merge(incoming)
    loadError.value = ''
    if (follow) await scrollBottom()
    positionReady.value = true
    if (follow) await markVisibleRead()
  } catch (error) {
    loadError.value = messageOf(error, '聊天记录读取失败，请重试')
  } finally {
    if (initial) {
      loading.value = false
      positionReady.value = true
    }
  }
}

async function poll() {
  if (!sending.value) await load(false)
}

// 展开每 5 秒刷新；收起仍为入口和宠物保持及时未读提示。
const messagePolling = createVisibilityPoller(
  poll,
  () => (props.visible && !document.hidden ? POLL_MS : 10_000),
  document,
  hasDesktopToolbar,
)

async function uploadInlineImage(file: File, done: (url: string | null, name: string) => void) {
  sendError.value = ''
  if (!file.type.startsWith('image/')) {
    sendError.value = '只能发送图片文件'
    done(null, file.name)
    return
  }
  if (file.size > MAX_IMAGE_BYTES) {
    sendError.value = '图片超过 10MB，请压缩后重试'
    done(null, file.name)
    return
  }
  uploadingImage.value = true
  try {
    const uploaded = await uploadSharedChatImage(file)
    if (!uploaded.id) throw new Error('图片未能持久化，请重试')
    done(uploaded.url, uploaded.name)
  } catch (error) {
    sendError.value = messageOf(error, '图片上传失败，草稿已保留')
    done(null, file.name)
  } finally {
    uploadingImage.value = false
  }
}

function insertEmoji(emoji: string) {
  draft.value += emoji
  emojiOpen.value = false
}

function onComposerKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) return
  if (
    !(event.target instanceof Element) ||
    !event.target.closest('textarea, [contenteditable="true"]')
  )
    return
  event.preventDefault()
  event.stopPropagation()
  void send()
}

watch(
  () => ui.chatDraftRequestVersion,
  () => {
    const addition = ui.consumeChatDraft()
    if (addition) draft.value += `${draft.value.trim() ? '\n\n' : ''}${addition}`
  },
)

function quote(message: SharedChatMessage) {
  replyingTo.value = message
  reactionMenuId.value = ''
}

async function send() {
  if (!canSend.value) return
  sending.value = true
  sendError.value = ''
  if (
    !sendAttempt ||
    sendAttempt.content !== draft.value ||
    sendAttempt.replyToId !== replyingTo.value?.id
  ) {
    sendAttempt = { content: draft.value, replyToId: replyingTo.value?.id, token: newToken() }
  }
  const sentDraft = draft.value
  const sentReply = replyingTo.value?.id
  try {
    const saved = await sendSharedChatMessage({
      clientToken: sendAttempt.token,
      content: draft.value,
      replyToId: replyingTo.value?.id,
    })
    merge([saved])
    if (draft.value === sentDraft && replyingTo.value?.id === sentReply) {
      composer.value?.clearSentContent?.(sentDraft)
      draft.value = ''
      replyingTo.value = null
      localStorage.removeItem(storageKey.value)
    }
    sendAttempt = null
    await scrollBottom()
  } catch (error) {
    sendError.value = messageOf(error, '发送失败，草稿已保留，请重试')
  } finally {
    sending.value = false
  }
}

async function toggleReaction(message: SharedChatMessage, emojiType: string) {
  reactingId.value = `${message.id}:${emojiType}`
  try {
    merge([await toggleSharedChatReaction(message.id, emojiType)])
    reactionMenuId.value = ''
  } catch (error) {
    sendError.value = messageOf(error, '表情回复失败，请重试')
  } finally {
    reactingId.value = ''
  }
}

watch([draft, replyingTo], () => {
  if (draft.value || replyingTo.value) {
    localStorage.setItem(
      storageKey.value,
      JSON.stringify({
        content: draft.value,
        replyToId: replyingTo.value?.id ?? '',
      }),
    )
  } else localStorage.removeItem(storageKey.value)
})

watch(
  messages,
  () => {
    ui.chatUnreadCount = unreadCount.value
    ui.chatLatestUnread =
      [...messages.value]
        .reverse()
        .find((message) => !message.read && message.author.id !== session.userId) || null
    emit('unread-change', unreadCount.value)
  },
  { immediate: true },
)
watch(
  () => ui.chatRefreshVersion,
  () => void load(false),
)

watch(
  () => props.visible,
  async (visible) => {
    if (!visible) {
      messagePolling.reschedule()
      return
    }
    positionReady.value = false
    await messagePolling.trigger()
    if (!unreadBoundaryId.value) {
      unreadBoundaryId.value = messages.value.find((message) => !message.read)?.id ?? ''
    }
    await scrollBottom()
    positionReady.value = true
    await markVisibleRead()
  },
)

watch(
  () => props.reviewRequestVersion,
  (version) => {
    if (!version) return
    reviewOpen.value = true
    initialReviewId.value = props.reviewId
  },
  { immediate: true },
)

onMounted(async () => {
  if (typeof ResizeObserver !== 'undefined' && stream.value) {
    streamResize = new ResizeObserver(() => {
      void onMediaLoaded()
    })
    streamResize.observe(stream.value)
  }
  let restoredReplyId = ''
  const stored =
    localStorage.getItem(storageKey.value) ??
    localStorage.getItem(`wao-shared-chat-draft-v1:${session.userId || 'unknown'}`)
  if (stored) {
    try {
      const parsed = JSON.parse(stored) as {
        content?: string
        imageId?: string
        imageName?: string
        replyToId?: string
      }
      draft.value = parsed.content ?? ''
      if (parsed.imageId)
        draft.value += `${draft.value ? '\n\n' : ''}![${parsed.imageName || '图片'}](/api/images/${parsed.imageId}/raw)`
      restoredReplyId = parsed.replyToId ?? ''
    } catch {
      draft.value = stored
    }
  }
  await load(true)
  if (restoredReplyId)
    replyingTo.value = messages.value.find((message) => message.id === restoredReplyId) ?? null
  messagePolling.start(false)
})

onBeforeUnmount(() => {
  disposed = true
  streamResize?.disconnect()
  messagePolling.stop()
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="imageMenu"
      class="chat-image-menu-mask"
      @pointerdown.self="imageMenu = null"
      @contextmenu.prevent="imageMenu = null"
    >
      <button
        class="chat-image-menu"
        :style="{ left: `${imageMenu.x}px`, top: `${imageMenu.y}px` }"
        @click="copyImage"
        @keydown.esc="imageMenu = null"
      >
        复制图片
      </button>
    </div>
  </Teleport>
  <main class="chat-page" :class="{ floating }">
    <header class="chat-header">
      <div>
        <p class="eyebrow">公共空间</p>
        <h1>
          聊天 <span v-if="unreadCount">{{ unreadCount }} 条未读</span>
        </h1>
        <p>和团队聊一聊，任务、图片和想法都能在这里分享。</p>
      </div>
      <div class="header-actions">
        <button class="review-trigger" type="button" @click="reviewOpen = true">
          <i class="ri-chat-history-line"></i><span>复盘</span>
        </button>
        <button
          v-if="floating"
          class="minimize-trigger"
          type="button"
          aria-label="最小化聊天"
          title="最小化聊天"
          @click="emit('minimize')"
        >
          <i class="ri-subtract-line"></i>
        </button>
      </div>
    </header>

    <section class="chat-shell">
      <div
        ref="stream"
        class="message-stream"
        :class="{ 'position-pending': !positionReady && messages.length }"
        aria-live="polite"
        @scroll.passive="onStreamScroll"
        @load.capture="onMediaLoaded"
        @click="openSharedLink"
        @contextmenu="imageContext"
      >
        <div v-if="loading" class="state-card">
          <i class="ri-loader-4-line spin"></i> 正在读取聊天记录…
        </div>
        <div v-else-if="loadError && !messages.length" class="state-card error-state">
          <span>{{ loadError }}</span
          ><button type="button" @click="load(true)">重新读取</button>
        </div>
        <div v-else-if="!messages.length" class="state-card empty-state">
          <i class="ri-chat-smile-2-line"></i><strong>还没有消息</strong
          ><span>从一句 Markdown 或一张图片开始。</span>
        </div>

        <template v-for="message in messages" :key="message.id">
          <div v-if="message.id === unreadBoundaryId" class="unread-divider">
            <span>以下是未读消息</span>
          </div>
          <article class="message-row" :class="{ mine: message.author.id === session.userId }">
            <span class="avatar" :style="{ background: message.author.color }">{{
              message.author.initial
            }}</span>
            <div class="message-body">
              <form v-if="editingId === message.id" class="message-edit" @submit.prevent="saveEdit">
                <label
                  >修改消息<textarea
                    v-model="editDraft"
                    aria-label="修改消息正文"
                    :disabled="editSaving"
                    rows="4"
                  />
                </label>
                <p v-if="editError" role="alert">{{ editError }}</p>
                <button
                  v-if="editOriginal !== message.content"
                  type="button"
                  :disabled="editSaving"
                  @click="acceptLatestMessage(message.content)"
                >
                  已核对下方最新消息，保留草稿重试
                </button>
                <button type="button" :disabled="editSaving" @click="editingId = ''">
                  收起，保留草稿
                </button>
                <button type="submit" :disabled="editSaving || !editDraft.trim()">
                  {{ editSaving ? '保存中…' : editError ? '重试保存' : '保存修改' }}
                </button>
              </form>
              <div class="message-meta">
                <strong>{{ message.author.name }}</strong>
                <time>{{ formatTime(message.createdAt) }}</time>
              </div>
              <button v-if="message.replyTo" class="reply-card" type="button">
                <b>{{ message.replyTo.authorName }}</b
                ><span>{{ message.replyTo.preview }}</span>
              </button>
              <div
                v-if="message.content"
                class="message-text md-body"
                v-html="renderMarkdown(message.content)"
              ></div>
              <a
                v-if="message.imageUrl"
                class="message-image"
                :href="message.imageUrl"
                target="_blank"
                rel="noopener noreferrer"
                :aria-label="`打开图片 ${message.imageName || ''}`"
              >
                <img :src="message.imageUrl" :alt="message.imageName || '聊天图片'" />
              </a>
              <div class="reaction-list">
                <button
                  v-for="reaction in message.reactions"
                  :key="reaction.emojiType"
                  type="button"
                  :class="{ reacted: reaction.reacted }"
                  :title="reaction.names.join('、')"
                  :disabled="!!reactingId"
                  @click="toggleReaction(message, reaction.emojiType)"
                >
                  {{ reaction.emoji }} <span>{{ reaction.count }}</span>
                </button>
              </div>
              <div class="message-actions">
                <button
                  v-if="
                    message.author.id === session.userId &&
                    message.source === 'WAO' &&
                    !message.deleted
                  "
                  type="button"
                  :disabled="editSaving"
                  @click="startEdit(message)"
                >
                  <i class="ri-edit-line"></i>修改
                </button>
                <button type="button" @click="quote(message)">
                  <i class="ri-reply-line"></i>引用
                </button>
                <div class="reaction-picker-wrap">
                  <button
                    type="button"
                    @click="reactionMenuId = reactionMenuId === message.id ? '' : message.id"
                  >
                    <i class="ri-emotion-line"></i>表情回复
                  </button>
                  <div v-if="reactionMenuId === message.id" class="reaction-picker">
                    <button
                      v-for="reaction in REACTIONS"
                      :key="reaction.type"
                      type="button"
                      :disabled="!!reactingId"
                      @click="toggleReaction(message, reaction.type)"
                    >
                      {{ reaction.emoji }}
                    </button>
                  </div>
                </div>
              </div>
              <div class="delivery">
                <i class="ri-check-double-line"></i>
                {{ message.readCount > 1 ? `${message.readCount} 人已读` : '已发送' }}
              </div>
            </div>
          </article>
        </template>
        <div v-if="loadError && messages.length" class="inline-error">
          {{ loadError }} <button type="button" @click="load(false)">重试</button>
        </div>
      </div>

      <button
        v-if="!atLatest && messages.length"
        class="latest-button"
        type="button"
        @click="goToLatest"
      >
        <i class="ri-arrow-down-line"></i>回到最新消息<span v-if="unreadCount"
          >（{{ unreadCount }} 条未读）</span
        >
      </button>
      <form class="composer" @submit.prevent="send" @keydown.capture="onComposerKeydown">
        <div v-if="replyingTo" class="replying">
          <i class="ri-reply-line"></i
          ><span
            >引用 <b>{{ replyingTo.author.name }}</b
            >：{{ replyingTo.content || '[图片]' }}</span
          >
          <button type="button" aria-label="取消引用" @click="replyingTo = null">
            <i class="ri-close-line"></i>
          </button>
        </div>
        <MarkdownEditor
          ref="composer"
          v-model="draft"
          :min-height="92"
          placeholder="输入消息，支持 Markdown 快捷输入和粘贴图片…"
          @upload-image="uploadInlineImage"
        />
        <div class="composer-footer">
          <div class="composer-status">
            <span v-if="uploadingFile">正在上传文件，请稍候…</span>
            <span v-else-if="uploadingImage">正在保存图片草稿…</span>
            <span v-else-if="speech.listening.value"
              >正在聆听，再点一次结束；转写后可修改再发送</span
            >
            <span v-else-if="speech.error.value" role="alert">{{ speech.error.value }}</span>
            <span v-else-if="sendError" class="send-error">{{ sendError }}</span>
            <span v-else>{{ draft ? '草稿已保存在本机' : 'Enter 发送 · Shift+Enter 换行' }}</span>
            <span :class="{ over: contentLength > 2000 }">{{ contentLength }}/2000</span>
          </div>
          <div class="composer-actions">
            <input ref="attachmentInput" type="file" hidden @change="uploadAttachment" />
            <button
              type="button"
              class="emoji-button"
              :disabled="uploadingFile || sending"
              @click="attachmentInput?.click()"
            >
              <i class="ri-attachment-line"></i>文件
            </button>
            <button
              v-if="speech.supported"
              type="button"
              class="emoji-button"
              :aria-pressed="speech.listening.value"
              :disabled="sending"
              title="使用浏览器语音服务，可能联网处理声音；转写后由你确认发送"
              @click="speech.toggle"
            >
              <i class="ri-mic-line"></i>{{ speech.listening.value ? '结束语音' : '语音输入' }}
            </button>
            <div class="emoji-wrap">
              <button type="button" class="emoji-button" @click="emojiOpen = !emojiOpen">
                <i class="ri-emotion-happy-line"></i>表情
              </button>
              <div v-if="emojiOpen" class="emoji-menu">
                <button
                  v-for="emoji in EMOJIS"
                  :key="emoji"
                  type="button"
                  @click="insertEmoji(emoji)"
                >
                  {{ emoji }}
                </button>
              </div>
            </div>
            <button class="send-button" type="submit" :disabled="!canSend">
              <i :class="sending ? 'ri-loader-4-line spin' : 'ri-send-plane-2-fill'"></i
              >{{ sending ? '发送中…' : '发送' }}
            </button>
          </div>
        </div>
      </form>
    </section>
    <ChatReviewPanel
      :open="reviewOpen"
      :initial-run-id="initialReviewId || undefined"
      @update:open="reviewOpen = $event"
    />
  </main>
</template>

<style scoped>
.message-stream.position-pending {
  visibility: hidden;
}
.latest-button {
  align-self: center;
  flex-shrink: 0;
  padding: 6px 14px;
  border: 1px solid var(--wao-line);
  border-radius: 20px;
  color: var(--wao-primary-text);
  background: var(--wao-bg-card);
  cursor: pointer;
}

.chat-page {
  height: calc(100vh - 112px);
  min-height: 560px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  box-sizing: border-box;
  padding: 0 clamp(16px, 2.2vw, 32px) 16px;
}
.chat-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}
.chat-header h1 {
  margin: 2px 0 4px;
  font-size: 25px;
  color: var(--wao-text);
}
.chat-header h1 span {
  font-size: 10px;
  color: #fff;
  background: var(--wao-primary);
  padding: 3px 7px;
  border-radius: 999px;
  vertical-align: middle;
}
.chat-header p {
  margin: 0;
  color: var(--wao-text-weak);
  font-size: 11px;
}
.eyebrow {
  color: var(--wao-primary) !important;
  font-weight: 700;
  letter-spacing: 0.12em;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.review-trigger {
  height: 36px;
  padding: 0 13px;
  border: 1px solid var(--wao-line-control);
  border-radius: 10px;
  background: var(--wao-primary);
  color: #fff;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.minimize-trigger {
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  border: 1px solid var(--wao-line-control);
  border-radius: 10px;
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
  display: grid;
  place-items: center;
  cursor: pointer;
}
.minimize-trigger:hover {
  background: var(--wao-bg-hover-2);
  color: var(--wao-text);
}
.chat-shell {
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
  overflow: hidden;
}
.message-edit {
  display: grid;
  gap: 8px;
  min-width: min(280px, 100%);
  padding: 10px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
}
.message-edit textarea {
  box-sizing: border-box;
  width: 100%;
  color: var(--wao-text);
  background: var(--wao-bg-card);
  resize: vertical;
}
.message-edit p {
  color: var(--wao-danger);
  overflow-wrap: anywhere;
}
.message-stream {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  min-width: 0;
  padding: 22px clamp(14px, 3vw, 32px);
  display: flex;
  flex-direction: column;
  gap: 15px;
  background: linear-gradient(
    180deg,
    color-mix(in srgb, var(--wao-primary) 3%, var(--wao-bg-card)),
    var(--wao-bg-card)
  );
}
.state-card {
  margin: auto;
  display: grid;
  justify-items: center;
  gap: 7px;
  color: var(--wao-text-weak);
}
.message-row {
  display: flex;
  gap: 9px;
  max-width: min(720px, 88%);
}
.message-row.mine {
  align-self: flex-end;
  flex-direction: row-reverse;
}
.avatar {
  flex: 0 0 29px;
  width: 29px;
  height: 29px;
  border-radius: 9px;
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 11px;
  font-weight: 700;
}
.message-body {
  min-width: 0;
  position: relative;
  max-width: 100%;
  margin-bottom: 18px;
}
.message-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 2px 5px;
  color: var(--wao-text-faint);
  font-size: 9px;
}
.message-meta strong {
  color: var(--wao-text-2);
  font-size: 10px;
}
.message-row.mine .message-meta {
  justify-content: flex-end;
}
.source-badge {
  padding: 1px 5px;
  border-radius: 999px;
  background: #eaf2ff;
  color: #4776ba;
}
.message-text {
  padding: 10px 12px;
  border: 1px solid var(--wao-line);
  border-radius: 4px 13px 13px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-size: 12px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}
.mine .message-text {
  border-color: color-mix(in srgb, var(--wao-primary) 20%, var(--wao-line));
  background: color-mix(in srgb, var(--wao-primary) 8%, var(--wao-bg-card));
  border-radius: 13px 4px 13px 13px;
}
.message-text :deep(p) {
  margin: 0 0 7px;
}
.message-text :deep(p:last-child) {
  margin-bottom: 0;
}
.message-text :deep(img) {
  display: block;
  max-width: 100%;
  max-height: 360px;
  border-radius: 9px;
  margin-top: 7px;
}
.message-text :deep(table) {
  display: block;
  max-width: 100%;
  overflow-x: auto;
}
.message-text :deep(pre) {
  max-width: 100%;
  overflow: auto;
  background: var(--wao-bg-sub);
  padding: 8px;
  border-radius: 7px;
}
.message-image img {
  display: block;
  max-width: min(420px, 100%);
  max-height: 340px;
  border-radius: 11px;
  margin-top: 6px;
  border: 1px solid var(--wao-line);
}
.reply-card,
.replying {
  border: 0;
  border-left: 3px solid var(--wao-primary);
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  padding: 6px 9px;
  display: flex;
  gap: 5px;
  font-size: 10px;
  max-width: 100%;
  overflow: hidden;
}
.reply-card span,
.replying span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.message-actions {
  opacity: 0;
  display: flex;
  gap: 6px;
  position: absolute;
  left: 0;
  bottom: 0;
  transform: translateY(100%);
  margin: 0;
  white-space: nowrap;
  transition: 0.15s;
}
.message-row.mine .message-actions {
  left: auto;
  right: 0;
  flex-direction: row-reverse;
}
.message-row:hover .message-actions,
.message-actions:focus-within {
  opacity: 1;
}
.message-actions > button,
.reaction-picker-wrap > button {
  border: 0;
  background: transparent;
  color: var(--wao-text-faint);
  font-size: 9px;
  cursor: pointer;
}
.reaction-picker-wrap,
.emoji-wrap {
  position: relative;
}
.reaction-picker,
.emoji-menu {
  position: absolute;
  z-index: 15;
  bottom: 24px;
  left: 0;
  display: flex;
  gap: 3px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  padding: 5px;
  box-shadow: 0 10px 25px rgba(20, 35, 31, 0.15);
}
.reaction-picker button,
.emoji-menu button {
  border: 0;
  background: transparent;
  font-size: 17px;
  padding: 4px;
  cursor: pointer;
}
.reaction-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 5px;
}
.reaction-list button {
  border: 1px solid var(--wao-line);
  background: var(--wao-bg-sub);
  border-radius: 999px;
  padding: 2px 7px;
  font-size: 11px;
  cursor: pointer;
}
.reaction-list button.reacted {
  border-color: var(--wao-primary);
  background: color-mix(in srgb, var(--wao-primary) 10%, var(--wao-bg-card));
}
.delivery {
  margin: 5px 3px 0;
  color: var(--wao-text-faint);
  font-size: 9px;
}
.delivery.failed,
.delivery.unavailable {
  color: var(--wao-warning-strong);
}
.delivery button {
  border: 0;
  background: transparent;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}
.unread-divider {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--wao-primary);
  font-size: 9px;
}
.unread-divider:before,
.unread-divider:after {
  content: '';
  height: 1px;
  flex: 1;
  background: color-mix(in srgb, var(--wao-primary) 35%, var(--wao-line));
}
.inline-error {
  align-self: center;
  color: var(--wao-danger);
  font-size: 10px;
}
.composer {
  border-top: 1px solid var(--wao-line);
  padding: 10px 14px;
  background: var(--wao-bg-card);
}
.replying {
  margin-bottom: 7px;
  border-radius: 7px;
}
.replying span {
  flex: 1;
}
.replying button {
  border: 0;
  background: transparent;
  color: var(--wao-text-faint);
}
.composer :deep(.md) {
  font-size: 12px;
}
.composer-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-top: 7px;
}
.composer-status {
  display: flex;
  gap: 10px;
  color: var(--wao-text-faint);
  font-size: 9px;
}
.composer-status .send-error,
.composer-status .over {
  color: var(--wao-danger);
}
.composer-actions {
  display: flex;
  gap: 7px;
}
.emoji-menu {
  bottom: 38px;
  right: 0;
  left: auto;
  width: 180px;
  display: grid;
  grid-template-columns: repeat(6, 1fr);
}
.emoji-button,
.send-button {
  height: 33px;
  border-radius: 9px;
  padding: 0 12px;
  cursor: pointer;
}
.emoji-button {
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
}
.send-button {
  border: 0;
  background: var(--wao-primary);
  color: #fff;
}
.send-button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.chat-page.floating {
  height: 100%;
  min-height: 0;
  gap: 10px;
  padding: 14px;
}
.floating .chat-header {
  align-items: center;
}
.floating .chat-header > div:first-child {
  min-width: 0;
  flex: 1;
}
.floating .chat-header h1 {
  margin: 0;
  font-size: 19px;
}
.floating .chat-header p {
  display: none;
}
.floating .header-actions {
  flex: 0 1 auto;
  min-width: 0;
}
.floating .chat-shell {
  border-radius: 13px;
}
.floating .message-stream {
  padding: 16px 18px;
}
.floating .composer {
  padding: 9px 11px;
}
.spin {
  display: inline-block;
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 640px) {
  .chat-page {
    height: calc(100dvh - 82px);
    min-height: 520px;
    padding: 0 8px 8px;
  }
  .chat-page.floating {
    height: 100dvh;
    min-height: 0;
    padding: 10px 8px 8px;
  }
  .chat-header {
    align-items: flex-start;
  }
  .chat-header p:not(.eyebrow) {
    display: none;
  }
  .review-trigger span {
    display: none;
  }
  .review-trigger {
    width: 36px;
    padding: 0;
    justify-content: center;
  }
  .message-stream {
    padding: 14px 10px;
  }
  .message-row {
    max-width: 94%;
  }
  .composer {
    padding: 8px;
  }
  .composer-footer {
    align-items: flex-end;
  }
  .composer-status {
    display: grid;
    gap: 2px;
  }
  .message-actions {
    opacity: 1;
    position: static;
    transform: none;
    margin-top: 4px;
  }
  .message-row.mine .message-actions {
    flex-direction: row;
  }
  .message-text {
    font-size: 12px;
  }
}
</style>

<style scoped>
.chat-image-menu-mask {
  position: fixed;
  inset: 0;
  z-index: 5000;
}
.chat-image-menu {
  position: absolute;
  width: 150px;
  padding: 10px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  box-shadow: 0 4px 16px #0002;
  cursor: pointer;
}
</style>

<style scoped>
.reaction-picker {
  display: grid;
  grid-template-columns: repeat(5, 32px);
  max-width: calc(100vw - 32px);
}
</style>
