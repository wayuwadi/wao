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
import {
  fetchSharedChatConfig,
  fetchSharedChatFeishuGroups,
  fetchSharedChatMessages,
  markSharedChatRead,
  retrySharedChatFeishu,
  saveSharedChatConfig,
  sendSharedChatMessage,
  toggleSharedChatReaction,
  uploadSharedChatImage,
  type SharedChatConfig,
  type SharedChatFeishuGroup,
  type SharedChatMessage,
} from '@/api/chat'
import { useSessionStore } from '@/stores/session'
import { renderMarkdown } from '@/utils/markdown'

const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
const POLL_MS = 5_000
const MAX_IMAGE_BYTES = 10 * 1024 * 1024
const EMOJIS = ['😀', '😂', '😊', '🥰', '👍', '👏', '🙏', '🎉', '✅', '❤️', '🔥', '🤔']
const REACTIONS = [
  { type: 'THUMBSUP', emoji: '👍' },
  { type: 'HEART', emoji: '❤️' },
  { type: 'SMILE', emoji: '😊' },
  { type: 'DONE', emoji: '✅' },
]

const session = useSessionStore()
const messages = ref<SharedChatMessage[]>([])
const draft = ref('')
const clientToken = ref('')
const replyingTo = ref<SharedChatMessage | null>(null)
const loading = ref(true)
const loadError = ref('')
const sending = ref(false)
const uploadingImage = ref(false)
const sendError = ref('')
const retryingId = ref('')
const reactingId = ref('')
const reactionMenuId = ref('')
const emojiOpen = ref(false)
const unreadBoundaryId = ref('')
const stream = ref<HTMLElement | null>(null)
const config = ref<SharedChatConfig | null>(null)
const groups = ref<SharedChatFeishuGroup[]>([])
const groupMenuOpen = ref(false)
const configLoading = ref(true)
const groupsLoading = ref(false)
const configSaving = ref(false)
const configError = ref('')
let pollTimer: ReturnType<typeof setTimeout> | null = null
let alive = true

const storageKey = computed(() => `wao-shared-chat-draft-v2:${session.userId || 'unknown'}`)
const contentLength = computed(() => Array.from(draft.value).length)
const canSend = computed(
  () =>
    !sending.value && !uploadingImage.value && contentLength.value <= 2_000 && !!draft.value.trim(),
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

async function scrollBottom() {
  await nextTick()
  if (stream.value) stream.value.scrollTop = stream.value.scrollHeight
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
  if (document.visibilityState !== 'visible' || !nearBottom()) return
  const last = messages.value[messages.value.length - 1]
  if (!last || messages.value.every((message) => message.read)) return
  try {
    await markSharedChatRead(last.id)
    messages.value = messages.value.map((message) => ({ ...message, read: true }))
  } catch (error) {
    loadError.value = messageOf(error, '已读状态同步失败，请重试')
  }
}

async function load(initial = false) {
  const shouldFollow = initial || nearBottom()
  try {
    const incoming = await fetchSharedChatMessages()
    if (initial && !unreadBoundaryId.value) {
      unreadBoundaryId.value = incoming.find((message) => !message.read)?.id ?? ''
    }
    merge(incoming)
    loadError.value = ''
    if (shouldFollow) {
      await scrollBottom()
      await markVisibleRead()
    }
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

async function loadConfig() {
  configLoading.value = true
  try {
    config.value = await fetchSharedChatConfig()
    configError.value = ''
  } catch (error) {
    configError.value = messageOf(error, '飞书群配置读取失败')
  } finally {
    configLoading.value = false
  }
}

async function toggleGroupMenu() {
  if (configSaving.value) return
  groupMenuOpen.value = !groupMenuOpen.value
  if (!groupMenuOpen.value || groups.value.length) return
  groupsLoading.value = true
  try {
    groups.value = await fetchSharedChatFeishuGroups()
    configError.value = ''
  } catch (error) {
    configError.value = messageOf(error, '真实飞书群读取失败，请检查自建应用配置')
  } finally {
    groupsLoading.value = false
  }
}

async function selectGroup(chatId: string) {
  configSaving.value = true
  try {
    config.value = await saveSharedChatConfig(chatId)
    configError.value = ''
    groupMenuOpen.value = false
  } catch (error) {
    configError.value = messageOf(error, '飞书群保存失败，原目标未改变')
  } finally {
    configSaving.value = false
  }
}

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

function quote(message: SharedChatMessage) {
  replyingTo.value = message
  reactionMenuId.value = ''
}

async function send() {
  if (!canSend.value) return
  sending.value = true
  sendError.value = ''
  if (!clientToken.value) clientToken.value = newToken()
  try {
    const saved = await sendSharedChatMessage({
      clientToken: clientToken.value,
      content: draft.value,
      replyToId: replyingTo.value?.id,
    })
    merge([saved])
    draft.value = ''
    replyingTo.value = null
    localStorage.removeItem(storageKey.value)
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

onMounted(async () => {
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
  await Promise.all([load(true), loadConfig()])
  if (restoredReplyId)
    replyingTo.value = messages.value.find((message) => message.id === restoredReplyId) ?? null
  pollTimer = setTimeout(poll, POLL_MS)
})

onBeforeUnmount(() => {
  alive = false
  if (pollTimer) clearTimeout(pollTimer)
})
</script>

<template>
  <main class="chat-page">
    <header class="chat-header">
      <div>
        <p class="eyebrow">公共空间</p>
        <h1>
          聊天 <span v-if="unreadCount">{{ unreadCount }} 条未读</span>
        </h1>
        <p>WAO 与所选飞书群共享同一条消息流；Markdown、图文、引用、已读和表情均保留真实状态。</p>
      </div>
      <div class="group-picker">
        <button
          class="group-trigger"
          type="button"
          :disabled="configLoading || configSaving"
          @click="toggleGroupMenu"
        >
          <i :class="configLoading || configSaving ? 'ri-loader-4-line spin' : 'ri-group-line'"></i>
          <span>{{
            configSaving ? '保存中…' : config?.configured ? config.chatName : '选择飞书群'
          }}</span>
          <i class="ri-arrow-down-s-line"></i>
        </button>
        <div v-if="groupMenuOpen" class="group-menu">
          <header><b>双向同步群</b><small>只显示自建应用真实所在群</small></header>
          <div v-if="groupsLoading" class="group-state">
            <i class="ri-loader-4-line spin"></i>正在读取…
          </div>
          <template v-else>
            <button
              type="button"
              :class="{ selected: !config?.configured }"
              @click="selectGroup('')"
            >
              <i class="ri-link-unlink-m"></i
              ><span><b>暂不桥接</b><small>WAO 消息仍会保存</small></span>
            </button>
            <button
              v-for="group in groups"
              :key="group.chatId"
              type="button"
              :class="{ selected: config?.chatId === group.chatId }"
              @click="selectGroup(group.chatId)"
            >
              <i class="ri-group-2-line"></i
              ><span
                ><b>{{ group.name }}</b
                ><small>机器人已在群内</small></span
              >
            </button>
            <div v-if="!groups.length" class="group-state">
              暂无真实群，请先在自动化中配置飞书自建应用。
            </div>
          </template>
          <div v-if="configError" class="config-error">{{ configError }}</div>
        </div>
      </div>
    </header>

    <section class="chat-shell">
      <div ref="stream" class="message-stream" aria-live="polite" @scroll.passive="markVisibleRead">
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
              <div class="message-meta">
                <strong>{{ message.author.name }}</strong>
                <span v-if="message.source === 'FEISHU'" class="source-badge">飞书</span>
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
              <div class="delivery" :class="message.feishuStatus.toLowerCase()">
                <template v-if="message.source === 'FEISHU'"
                  ><i class="ri-arrow-left-right-line"></i> 来自飞书</template
                >
                <template v-else-if="message.feishuStatus === 'SENT'">
                  <i class="ri-check-double-line"></i>
                  {{
                    message.feishuReadCount
                      ? `飞书 ${message.feishuReadCount} 人已读`
                      : message.readCount > 1
                        ? `${message.readCount} 人已读`
                        : '已同步飞书'
                  }}
                </template>
                <template v-else-if="message.feishuStatus === 'PENDING'"
                  ><i class="ri-loader-4-line spin"></i> 正在同步飞书</template
                >
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
        </template>
        <div v-if="loadError && messages.length" class="inline-error">
          {{ loadError }} <button type="button" @click="load(false)">重试</button>
        </div>
      </div>

      <form
        class="composer"
        @submit.prevent="send"
        @keydown.ctrl.enter.prevent="send"
        @keydown.meta.enter.prevent="send"
      >
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
          v-model="draft"
          :min-height="92"
          placeholder="输入消息，支持 Markdown 快捷输入和粘贴图片…"
          @upload-image="uploadInlineImage"
        />
        <div class="composer-footer">
          <div class="composer-status">
            <span v-if="uploadingImage">正在保存图片草稿…</span>
            <span v-else-if="sendError" class="send-error">{{ sendError }}</span>
            <span v-else>{{ draft ? '草稿已保存在本机' : 'Ctrl / ⌘ + Enter 发送' }}</span>
            <span :class="{ over: contentLength > 2000 }">{{ contentLength }}/2000</span>
          </div>
          <div class="composer-actions">
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
  </main>
</template>

<style scoped>
.chat-page {
  height: calc(100vh - 112px);
  min-height: 560px;
  display: flex;
  flex-direction: column;
  gap: 16px;
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
.group-picker {
  position: relative;
}
.group-trigger {
  width: 180px;
  height: 36px;
  border: 1px solid var(--wao-line-control);
  border-radius: 10px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 0 10px;
  cursor: pointer;
}
.group-trigger span {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
}
.group-menu {
  position: absolute;
  z-index: 30;
  right: 0;
  top: 43px;
  width: min(310px, calc(100vw - 40px));
  max-height: 420px;
  overflow: auto;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 8px;
  box-shadow: 0 18px 48px rgba(20, 35, 31, 0.2);
}
.group-menu header {
  display: grid;
  padding: 5px 7px 9px;
}
.group-menu header small,
.group-menu button small {
  color: var(--wao-text-faint);
  font-size: 9px;
}
.group-menu > button {
  width: 100%;
  border: 0;
  background: transparent;
  border-radius: 9px;
  padding: 8px;
  display: grid;
  grid-template-columns: 22px 1fr;
  text-align: left;
  color: var(--wao-text-2);
  cursor: pointer;
}
.group-menu > button.selected,
.group-menu > button:hover {
  background: color-mix(in srgb, var(--wao-primary) 9%, transparent);
}
.group-menu > button span {
  display: grid;
}
.group-state,
.config-error {
  padding: 9px;
  color: var(--wao-text-weak);
  font-size: 10px;
}
.config-error {
  color: var(--wao-danger);
  background: var(--wao-danger-tint);
  border-radius: 8px;
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
.message-stream {
  flex: 1;
  min-height: 0;
  overflow: auto;
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
.message-text :deep(pre) {
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
  margin-top: 4px;
  transition: 0.15s;
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
  }
  .chat-header {
    align-items: flex-start;
  }
  .chat-header p:not(.eyebrow) {
    display: none;
  }
  .group-trigger {
    width: 145px;
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
  }
  .message-text {
    font-size: 12px;
  }
}
</style>
