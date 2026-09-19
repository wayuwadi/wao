<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  createAssistantConversation,
  deleteAssistantConversation,
  fetchAssistantConversation,
  fetchAssistantConversations,
  fetchAssistantSourceOptions,
  fetchAssistantStatus,
  fetchAiCapabilities,
  streamChat,
  type AssistantConversation,
  type AssistantAssetType,
  type AssistantSourceFilter,
  type AssistantSourceOptions,
  type AssistantStatus,
  type AiCapability,
  type ChatMessage,
} from '@/api/assistant'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { isDesktopWorkspace } from '@/utils/desktopWorkspace'
import WaoWebPet from '@/components/assistant/WaoWebPet.vue'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'

interface Msg {
  id?: string
  role: 'user' | 'assistant'
  content: string
  streaming?: boolean
}

const props = defineProps<{ modelValue: boolean; embedded?: boolean; title?: string }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const router = useRouter()
const ui = useUiStore()
const session = useSessionStore()
const open = ref(false)
const messages = ref<Msg[]>([])
const conversations = ref<AssistantConversation[]>([])
const activeConversationId = ref<string | null>(null)
const input = ref('')
const draftError = ref('')
const sendError = ref('')
const savedReply = ref(false)
let selectionVersion = 0
const draftCache = new Map<string, string>()
function draftKey(id: string | null = activeConversationId.value) {
  return `wao-assistant-draft-v1:${session.userId}:${id || 'new'}`
}
function readDraft(id: string | null) {
  const key = draftKey(id)
  if (draftCache.has(key)) return draftCache.get(key) || ''
  try { return localStorage.getItem(key) || '' } catch { return '' }
}
function saveDraft(value: string, id: string | null = activeConversationId.value) {
  const key = draftKey(id)
  draftCache.set(key, value)
  try {
    if (value) localStorage.setItem(key, value)
    else localStorage.removeItem(key)
    draftError.value = ''
  } catch { draftError.value = '本机草稿保存失败，请保留输入后再刷新' }
}
function activateConversation(id: string | null) {
  saveDraft(input.value)
  activeConversationId.value = id
  input.value = readDraft(id)
  sendError.value = ''
  savedReply.value = false
  try { localStorage.setItem(`wao-assistant-active-v1:${session.userId}`, id || 'new') } catch { /* 草稿仍在内存 */ }
}
watch(input, value => saveDraft(value), { flush: 'sync' })
const historySearch = ref('')
const sending = ref(false)
const statusLoading = ref(false)
const listLoading = ref(false)
const conversationLoading = ref(false)
const historyLoading = computed(() => listLoading.value || conversationLoading.value)
let listVersion = 0
let statusVersion = 0
const historyError = ref('')
const sidebarOpen = ref(true)
const sourcePanelOpen = ref(false)
const sourceOptionsLoading = ref(false)
const sourceOptionsError = ref('')
const sourceOptions = ref<AssistantSourceOptions>({ chats: [], senders: [], projects: [] })
const sourceFilter = reactive<{
  assetTypes: AssistantAssetType[]
  chatIds: string[]
  senders: string[]
  dateFrom: string
  dateTo: string
  projectIds: string[]
}>({ assetTypes: ['memory', 'chat', 'knowledge', 'project', 'task'], chatIds: [], senders: [], dateFrom: '', dateTo: '', projectIds: [] })
const assistantStatus = ref<AssistantStatus>({ configured: false, message: '正在检查本地 Codex…' })
const capabilities = ref<AiCapability[]>([])
function initialCapability() {
  try { return localStorage.getItem('wao-assistant-capability-v1') || 'local-codex' } catch { return 'local-codex' }
}
const capabilityId = ref(initialCapability())
const listRef = ref<HTMLElement | null>(null)
const textareaRef = ref<HTMLTextAreaElement | null>(null)
let statusRefreshTimer: ReturnType<typeof setInterval> | null = null

const QUICK_COMMANDS = [
  { id: 'summary', title: '总结当前页面', icon: 'ri-file-text-line',
    build: () => ({ message: '请用一段话总结当前页面的核心内容和关键数据。', context: capturePageContext() }) },
  { id: 'task', title: '规划一个任务', icon: 'ri-checkbox-circle-line',
    build: () => ({ message: '请帮我把想法整理成清晰的任务标题、描述和验收标准。', context: undefined }) },
  { id: 'project', title: '梳理项目计划', icon: 'ri-stack-line',
    build: () => ({ message: '请帮我梳理项目目标、关键里程碑和下一步行动。', context: undefined }) },
  { id: 'error', title: '分析错误', icon: 'ri-bug-line',
    build: () => ({ message: '请结合当前页面，帮我分析错误并给出排查步骤。', context: capturePageContext() }) },
]

const ASSET_TYPES: Array<{ id: AssistantAssetType; name: string; icon: string }> = [
  { id: 'memory', name: '个人记忆', icon: 'ri-brain-line' },
  { id: 'chat', name: '群聊', icon: 'ri-chat-3-line' },
  { id: 'knowledge', name: '知识库', icon: 'ri-book-open-line' },
  { id: 'project', name: '项目', icon: 'ri-stack-line' },
  { id: 'task', name: 'Task', icon: 'ri-checkbox-circle-line' },
]

const filteredConversations = computed(() => {
  const keyword = historySearch.value.trim().toLowerCase()
  if (!keyword) return conversations.value
  return conversations.value.filter((item) =>
    `${item.title} ${item.preview || ''}`.toLowerCase().includes(keyword),
  )
})

const activeConversation = computed(() =>
  conversations.value.find((item) => item.id === activeConversationId.value),
)

const chatEnabled = computed(() => sourceFilter.assetTypes.includes('chat'))
const projectAssetsEnabled = computed(() =>
  sourceFilter.assetTypes.includes('project') || sourceFilter.assetTypes.includes('task'),
)
const activeFilterCount = computed(() => {
  let count = sourceFilter.assetTypes.length === ASSET_TYPES.length ? 0 : 1
  count += sourceFilter.chatIds.length + sourceFilter.senders.length + sourceFilter.projectIds.length
  if (sourceFilter.dateFrom || sourceFilter.dateTo) count += 1
  return count
})
const sourceSummary = computed(() => {
  if (!activeFilterCount.value) return '全部 WAO 资产'
  const types = ASSET_TYPES.filter((item) => sourceFilter.assetTypes.includes(item.id)).map((item) => item.name)
  const details: string[] = [types.join('、') || '未选资产']
  if (sourceFilter.chatIds.length) details.push(`${sourceFilter.chatIds.length} 个群`)
  if (sourceFilter.senders.length) details.push(`${sourceFilter.senders.length} 位发言人`)
  if (sourceFilter.dateFrom || sourceFilter.dateTo) details.push('指定日期')
  if (sourceFilter.projectIds.length) details.push(`${sourceFilter.projectIds.length} 个项目`)
  return details.join(' · ')
})

function close() { emit('update:modelValue', false) }

function capturePageContext(): string {
  const route = router.currentRoute.value
  const name = String(route.meta.module || route.name || '未知页面')
  const body = (document.querySelector('.page-body') || document.body) as HTMLElement
  const text = body?.innerText?.slice(0, 2000) || ''
  return `当前页面：${name} (${route.fullPath})\n\n页面内容摘要：\n${text}`
}

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

function focusInput() {
  nextTick(() => textareaRef.value?.focus())
}

async function loadStatus() {
  const version = ++statusVersion
  statusLoading.value = true
  try {
    const status = await fetchAssistantStatus(capabilityId.value)
    if (version === statusVersion) assistantStatus.value = status
  } catch {
    if (version === statusVersion) assistantStatus.value = { configured: false, message: '无法读取 AI 状态，请稍后重试' }
  } finally {
    if (version === statusVersion) statusLoading.value = false
  }
}

async function loadCapabilities() {
  try {
    capabilities.value = await fetchAiCapabilities()
    if (!capabilities.value.some((item) => item.id === capabilityId.value)) {
      capabilityId.value = capabilities.value[0]?.id || 'local-codex'
    }
  } catch {
    capabilities.value = [{ id: 'local-codex', label: '本地 Codex', provider: 'codex',
      executionMode: 'RUNNER', available: false, message: '能力列表读取失败' }]
  }
}

async function loadSourceOptions() {
  if (sourceOptionsLoading.value) return
  sourceOptionsLoading.value = true
  sourceOptionsError.value = ''
  try {
    sourceOptions.value = await fetchAssistantSourceOptions()
  } catch {
    sourceOptionsError.value = '来源筛选加载失败，请重试'
  } finally {
    sourceOptionsLoading.value = false
  }
}

function toggleAssetType(type: AssistantAssetType) {
  const index = sourceFilter.assetTypes.indexOf(type)
  if (index >= 0) {
    if (sourceFilter.assetTypes.length === 1) return
    sourceFilter.assetTypes.splice(index, 1)
  } else {
    sourceFilter.assetTypes.push(type)
  }
}

function toggleFilterValue(values: string[], value: string) {
  const index = values.indexOf(value)
  if (index >= 0) values.splice(index, 1)
  else values.push(value)
}

function resetSourceFilter() {
  sourceFilter.assetTypes.splice(0, sourceFilter.assetTypes.length, 'memory', 'chat', 'knowledge', 'project', 'task')
  sourceFilter.chatIds.splice(0)
  sourceFilter.senders.splice(0)
  sourceFilter.projectIds.splice(0)
  sourceFilter.dateFrom = ''
  sourceFilter.dateTo = ''
}

function currentSourceFilter(): AssistantSourceFilter {
  return {
    assetTypes: [...sourceFilter.assetTypes],
    chatIds: sourceFilter.chatIds.length ? [...sourceFilter.chatIds] : undefined,
    senders: sourceFilter.senders.length ? [...sourceFilter.senders] : undefined,
    dateFrom: sourceFilter.dateFrom || undefined,
    dateTo: sourceFilter.dateTo || undefined,
    projectIds: sourceFilter.projectIds.length ? [...sourceFilter.projectIds] : undefined,
  }
}

async function loadConversations(selectLatest = false) {
  const version = selectionVersion
  const listRequest = ++listVersion
  listLoading.value = true
  historyError.value = ''
  try {
    const items = await fetchAssistantConversations()
    if (listRequest !== listVersion) return
    conversations.value = items
    if (version !== selectionVersion) return
    const latest = conversations.value[0]
    if (selectLatest && !activeConversationId.value && !input.value) {
      let preferred: string | null = null
      try { preferred = localStorage.getItem(`wao-assistant-active-v1:${session.userId}`) } catch { /* 使用最近会话 */ }
      const selected = conversations.value.find(item => item.id === preferred) || latest
      if (preferred !== 'new' && selected) await selectConversation(selected.id)
    }
  } catch {
    if (listRequest === listVersion) historyError.value = '历史会话加载失败'
  } finally {
    if (listRequest === listVersion) listLoading.value = false
  }
}

async function selectConversation(conversationId: string) {
  if (sending.value) return
  const version = ++selectionVersion
  activateConversation(conversationId)
  messages.value = []
  conversationLoading.value = true
  historyError.value = ''
  try {
    const detail = await fetchAssistantConversation(conversationId)
    if (version !== selectionVersion) return
    messages.value = detail.messages.map((item) => ({
      id: item.id,
      role: item.role,
      content: item.content,
    }))
    sidebarOpen.value = window.innerWidth > 760
    scrollToBottom()
    focusInput()
  } catch {
    if (version === selectionVersion) historyError.value = '这条会话暂时无法读取，请重试'
  } finally {
    if (version === selectionVersion) conversationLoading.value = false
  }
}

function newChat() {
  if (sending.value) return
  ++selectionVersion
  activateConversation(null)
  conversationLoading.value = false
  historyError.value = ''
  messages.value = []
  sidebarOpen.value = window.innerWidth > 760
  focusInput()
}

/** 页面可以把一个可编辑问题带入新会话，但不能替用户自动发送。 */
function applyAssistantDraft() {
  const draft = ui.assistantDraft.trim()
  if (!draft || !open.value || sending.value) return false
  ++selectionVersion
  activateConversation(null)
  messages.value = []
  input.value = input.value ? `${input.value}\n\n${draft}` : draft
  sidebarOpen.value = window.innerWidth > 760
  ui.consumeAssistantDraft()
  focusInput()
  return true
}

async function removeConversation(event: MouseEvent, conversation: AssistantConversation) {
  event.stopPropagation()
  if (sending.value) return
  if (!window.confirm(`删除“${conversation.title}”及其聊天记录？`)) return
  try {
    await deleteAssistantConversation(conversation.id)
    const wasActive = activeConversationId.value === conversation.id
    conversations.value = conversations.value.filter((item) => item.id !== conversation.id)
    if (wasActive) { input.value = ''; newChat() }
    saveDraft('', conversation.id)
  } catch (error) {
    historyError.value = error instanceof Error ? error.message : '删除失败，请重试'
  }
}

async function ensureConversation(): Promise<string> {
  if (activeConversationId.value) return activeConversationId.value
  const created = await createAssistantConversation()
  conversations.value.unshift(created)
  const draft = input.value
  activateConversation(created.id)
  input.value = draft
  saveDraft('', null)
  return created.id
}

async function send(text?: string, context?: string) {
  const msg = (text ?? input.value).trim()
  if (!msg || sending.value || historyLoading.value || historyError.value || !assistantStatus.value.configured) return
  sending.value = true
  sendError.value = ''
  savedReply.value = false
  sourcePanelOpen.value = false
  const originalDraftId = activeConversationId.value
  // 在服务端返回完整答复前保留问题，刷新或网络失败后仍可恢复。
  input.value = msg
  try {
    const conversationId = await ensureConversation()
    input.value = msg
    const history: ChatMessage[] = messages.value.slice(-10).map(item => ({ role: item.role, content: item.content }))
    messages.value.push({ role: 'user', content: msg })
    const assistantMsg = reactive<Msg>({ role: 'assistant', content: '', streaming: true })
    messages.value.push(assistantMsg)
    scrollToBottom()
    try {
      for await (const token of streamChat(msg, history, context, conversationId,
        currentSourceFilter(), capabilityId.value)) {
        assistantMsg.content += token
        scrollToBottom()
      }
      input.value = ''
      saveDraft('', originalDraftId)
      savedReply.value = true
    } catch (error) {
      sendError.value = error instanceof Error ? error.message : '请求失败，请重试'
      // 错误不伪装成模型回复；重新读取持久化历史，避免把乐观消息带入下一轮。
      try {
        const detail = await fetchAssistantConversation(conversationId)
        messages.value = detail.messages.map(item => ({ ...item }))
      } catch { messages.value = messages.value.filter(item => item !== assistantMsg) }
    } finally { assistantMsg.streaming = false }
    await loadConversations()
  } catch (error) {
    sendError.value = error instanceof Error ? error.message : '无法创建会话，请重试'
  } finally {
    sending.value = false
    scrollToBottom()
    focusInput()
  }
}

function retryHistory() {
  if (activeConversationId.value) void selectConversation(activeConversationId.value)
  else void loadConversations(true)
}
function handleEnter(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229 || event.shiftKey) return
  event.preventDefault()
  void send()
}

function quick(cmd: typeof QUICK_COMMANDS[number]) {
  const request = cmd.build()
  void send(request.message, request.context)
}

function stopStatusRefresh() {
  if (statusRefreshTimer) clearInterval(statusRefreshTimer)
  statusRefreshTimer = null
}

function startStatusRefresh() {
  stopStatusRefresh()
  statusRefreshTimer = setInterval(() => {
    if (!assistantStatus.value.configured) void loadStatus()
  }, 5_000)
}

function formatTime(value: string): string {
  const time = new Date(value)
  if (Number.isNaN(time.getTime())) return ''
  const now = new Date()
  const sameDay = time.toDateString() === now.toDateString()
  return sameDay
    ? time.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    : time.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

function renderMarkdown(text: string): string {
  if (!text) return ''
  return DOMPurify.sanitize(marked.parse(text, { async: false }) as string)
}

watch(() => props.modelValue || props.embedded, (value) => {
  open.value = Boolean(value)
  if (value) {
    sidebarOpen.value = window.innerWidth > 760
    void loadStatus()
    void loadCapabilities()
    const hasDraft = applyAssistantDraft()
    void loadConversations(!hasDraft)
    void loadSourceOptions()
    startStatusRefresh()
    focusInput()
  } else {
    stopStatusRefresh()
  }
})
watch(() => ui.assistantDraft, () => {
  applyAssistantDraft()
})
watch(open, (value) => { if (!value) emit('update:modelValue', false) })
watch(capabilityId, (value) => {
  try { localStorage.setItem('wao-assistant-capability-v1', value) } catch { /* 当前会话仍可使用 */ }
  assistantStatus.value = { configured: false, message: '正在检查所选 AI 能力…' }
  void loadStatus()
})

onMounted(() => {
  void loadStatus()
  void loadCapabilities()
  input.value = readDraft(null)
  if (props.modelValue || props.embedded) {
    open.value = true
    sidebarOpen.value = window.innerWidth > 760
    const hasDraft = applyAssistantDraft()
    void loadConversations(!hasDraft)
    void loadSourceOptions()
    startStatusRefresh()
  }
})
onBeforeUnmount(stopStatusRefresh)
</script>

<template>
  <Teleport to="body" :disabled="embedded">
    <Transition name="assistant">
      <div v-if="open" class="assistant-overlay" :class="{ embedded }" @click.self="!embedded && close()">
        <section class="assistant-workspace">
          <aside class="history-sidebar" :class="{ visible: sidebarOpen }">
            <div class="history-brand">
              <span class="brand-icon"><i class="ri-robot-2-line"></i></span>
              <div><b>{{ title || (embedded ? 'ChatGPT对话' : 'WAO AI') }}</b><small>我的问答知识库</small></div>
            </div>
            <button class="new-chat-btn" :disabled="sending" @click="newChat">
              <i class="ri-add-line"></i><span>新对话</span>
            </button>
            <label class="history-search">
              <i class="ri-search-line"></i>
              <input v-model="historySearch" placeholder="搜索聊天记录" />
            </label>
            <div class="history-label"><span>聊天记录</span><span>{{ conversations.length }}</span></div>
            <div class="history-list">
              <div v-if="historyLoading && conversations.length === 0" class="history-empty">
                <i class="ri-loader-4-line spin"></i>正在加载…
              </div>
              <div v-else-if="historyError && conversations.length === 0" class="history-empty error">
                {{ historyError }}<button @click="loadConversations(true)">重试</button>
              </div>
              <div v-else-if="filteredConversations.length === 0" class="history-empty">
                {{ historySearch ? '没有匹配的记录' : '开始第一次对话吧' }}
              </div>
              <button
                v-for="conversation in filteredConversations"
                :key="conversation.id"
                class="history-item"
                :class="{ active: conversation.id === activeConversationId }"
                :disabled="sending"
                @click="selectConversation(conversation.id)"
              >
                <span class="history-copy">
                  <b>{{ conversation.title }}</b>
                  <small>{{ conversation.preview || '空白会话' }}</small>
                </span>
                <span class="history-meta">
                  <time>{{ formatTime(conversation.updatedAt) }}</time>
                  <span class="history-delete" title="删除会话" @click="removeConversation($event, conversation)">
                    <i class="ri-delete-bin-line"></i>
                  </span>
                </span>
              </button>
            </div>
          </aside>

          <button v-if="sidebarOpen" class="mobile-sidebar-mask" aria-label="关闭历史记录" @click="sidebarOpen = false"></button>

          <main class="chat-main">
            <header class="chat-head">
              <div class="chat-heading">
                <button class="icon-btn sidebar-toggle" title="聊天记录" @click="sidebarOpen = !sidebarOpen">
                  <i class="ri-menu-line"></i>
                </button>
                <div>
                  <b>{{ activeConversation?.title || '新对话' }}</b>
                  <small :class="{ online: assistantStatus.configured }">
                    <i :class="assistantStatus.configured ? 'ri-checkbox-blank-circle-fill' : 'ri-error-warning-line'"></i>
                    {{ assistantStatus.message }}
                  </small>
                </div>
              </div>
              <div class="head-actions">
                <button class="head-new" :disabled="sending" @click="newChat"><i class="ri-add-line"></i>新对话</button>
                <button v-if="!embedded" class="icon-btn" title="关闭" @click="close"><i class="ri-close-line"></i></button>
              </div>
            </header>

            <div ref="listRef" class="assistant-list">
              <div v-if="!assistantStatus.configured" class="assistant-status">
                <i :class="statusLoading ? 'ri-loader-4-line spin' : 'ri-error-warning-line'"></i>
                <span>{{ assistantStatus.message }}</span>
                <button :disabled="statusLoading" @click="loadStatus">重试</button>
              </div>
              <div v-if="historyError && conversations.length > 0" class="inline-error">
                <i class="ri-error-warning-line"></i>{{ historyError }}<button @click="retryHistory">重试</button>
              </div>
              <div v-if="historyLoading && activeConversationId && messages.length === 0" class="chat-loading">
                <i class="ri-loader-4-line spin"></i>正在读取聊天记录…
              </div>
              <div v-else-if="messages.length === 0" class="welcome">
                <span class="welcome-icon"><i class="ri-robot-2-line"></i></span>
                <h2>有什么可以帮你？</h2>
                <p>所选 AI 能力会检索群聊、知识库、项目和 Task，问答也会保存在你的个人历史中。</p>
                <div class="welcome-actions">
                  <button
                    v-for="cmd in QUICK_COMMANDS"
                    :key="cmd.id"
                    :disabled="sending || !assistantStatus.configured"
                    @click="quick(cmd)"
                  >
                    <i :class="cmd.icon"></i><span>{{ cmd.title }}</span>
                  </button>
                </div>
              </div>
              <div v-else class="message-column">
                <article v-for="(message, index) in messages" :key="message.id || index" class="msg" :class="message.role">
                  <div v-if="message.role === 'assistant'" class="avatar"><i class="ri-robot-2-line"></i></div>
                  <div class="bubble">
                    <div v-if="message.role === 'assistant'" class="md" v-html="renderMarkdown(message.content)"></div>
                    <pre v-else class="user-text">{{ message.content }}</pre>
                    <span v-if="message.streaming" class="cursor">▊</span>
                  </div>
                </article>
              </div>
            </div>

            <footer class="composer-wrap">
              <div v-if="sendError" class="inline-error" role="alert">{{ sendError }}<button :disabled="sending" @click="send()">重试发送</button></div>
              <div class="draft-state" role="status">{{ draftError || (sending ? '正在回复 · 问题已保留在本机' : input ? '草稿已保存在本机' : savedReply ? '对话已保存到 WAO' : '') }}</div>
              <div v-if="sourcePanelOpen" class="source-filter-panel">
                <div class="source-filter-head">
                  <div><b>检索 WAO 资产</b><small>筛选只影响下一次及后续提问</small></div>
                  <button :disabled="!activeFilterCount" @click="resetSourceFilter">恢复全部</button>
                </div>
                <div v-if="sourceOptionsError" class="source-options-error">
                  {{ sourceOptionsError }}<button @click="loadSourceOptions">重试</button>
                </div>
                <div v-else-if="sourceOptionsLoading" class="source-options-loading">
                  <i class="ri-loader-4-line spin"></i>正在读取资产范围…
                </div>
                <template v-else>
                  <section class="filter-section">
                    <label>资产类型</label>
                    <div class="filter-chips">
                      <button
                        v-for="type in ASSET_TYPES"
                        :key="type.id"
                        :class="{ selected: sourceFilter.assetTypes.includes(type.id) }"
                        @click="toggleAssetType(type.id)"
                      ><i :class="type.icon"></i>{{ type.name }}</button>
                    </div>
                  </section>
                  <section v-if="chatEnabled" class="filter-section">
                    <label>群聊</label>
                    <div v-if="sourceOptions.chats.length" class="filter-chips scrollable">
                      <button
                        v-for="chat in sourceOptions.chats"
                        :key="chat.id"
                        :class="{ selected: sourceFilter.chatIds.includes(chat.id) }"
                        @click="toggleFilterValue(sourceFilter.chatIds, chat.id)"
                      >{{ chat.name }}</button>
                    </div>
                    <small v-else>还没有配置飞书群聊同步</small>
                  </section>
                  <section v-if="chatEnabled && sourceOptions.senders.length" class="filter-section">
                    <label>发言人</label>
                    <div class="filter-chips scrollable">
                      <button
                        v-for="sender in sourceOptions.senders"
                        :key="sender"
                        :class="{ selected: sourceFilter.senders.includes(sender) }"
                        @click="toggleFilterValue(sourceFilter.senders, sender)"
                      >{{ sender }}</button>
                    </div>
                  </section>
                  <section v-if="chatEnabled" class="filter-section date-filter">
                    <label>日期范围</label>
                    <input v-model="sourceFilter.dateFrom" type="date" :min="sourceOptions.earliestChatDate" :max="sourceFilter.dateTo || sourceOptions.latestChatDate" />
                    <span>至</span>
                    <input v-model="sourceFilter.dateTo" type="date" :min="sourceFilter.dateFrom || sourceOptions.earliestChatDate" :max="sourceOptions.latestChatDate" />
                  </section>
                  <section v-if="projectAssetsEnabled && sourceOptions.projects.length" class="filter-section">
                    <label>所属项目</label>
                    <div class="filter-chips scrollable projects">
                      <button
                        v-for="project in sourceOptions.projects"
                        :key="project.id"
                        :class="{ selected: sourceFilter.projectIds.includes(project.id) }"
                        @click="toggleFilterValue(sourceFilter.projectIds, project.id)"
                      >{{ project.name }}</button>
                    </div>
                  </section>
                </template>
              </div>
              <div class="assistant-input" :class="{ disabled: !assistantStatus.configured }">
                <label class="capability-picker">
                  <i class="ri-sparkling-2-line"></i>
                  <select v-model="capabilityId" :disabled="sending" aria-label="本次 AI 能力">
                    <option v-for="capability in capabilities" :key="capability.id" :value="capability.id">
                      {{ capability.label }}{{ capability.model ? ` · ${capability.model}` : '' }}{{ capability.available ? '' : ' · 当前不可用' }}
                    </option>
                  </select>
                </label>
                <button class="source-summary" :class="{ active: sourcePanelOpen || activeFilterCount }" @click="sourcePanelOpen = !sourcePanelOpen">
                  <i class="ri-database-2-line"></i><span>{{ sourceSummary }}</span>
                  <em v-if="activeFilterCount">{{ activeFilterCount }}</em>
                  <i :class="sourcePanelOpen ? 'ri-arrow-down-s-line' : 'ri-arrow-up-s-line'"></i>
                </button>
                <textarea
                  ref="textareaRef"
                  v-model="input"
                  placeholder="给 WAO AI 发送消息"
                  rows="2"
                  :disabled="sending"
                  maxlength="8000"
                  @keydown.enter="handleEnter"
                ></textarea>
                <div class="composer-foot">
                  <span><i class="ri-global-line"></i>只读对话 · 按需联网 · Enter 发送，Shift+Enter 换行</span>
                  <button class="send-btn" :disabled="!input.trim() || sending || historyLoading || !!historyError || !assistantStatus.configured" @click="send()">
                    <i :class="sending ? 'ri-loader-4-line spin' : 'ri-arrow-up-line'"></i>
                  </button>
                </div>
              </div>
            </footer>
          </main>
        </section>
      </div>
    </Transition>

    <WaoWebPet v-if="!isDesktopWorkspace() && !open && !embedded && router.currentRoute.value.name !== 'pet-world'" :assistant-status="assistantStatus" />
  </Teleport>
</template>

<style scoped>
.assistant-overlay.embedded { position: absolute; inset: 0; z-index: 0; padding: 0; background: transparent; backdrop-filter: none; }
.embedded .assistant-workspace { width: 100%; border-radius: 12px; box-shadow: none; }
.draft-state { width: min(780px, 100%); min-height: 18px; margin: 0 auto 4px; color: var(--wao-text-weak); font-size: 11px; }
.inline-error button { border: 0; background: transparent; color: inherit; cursor: pointer; flex-shrink: 0; }
.chat-heading > div { min-width: 0; }
.chat-head { flex-shrink: 0; gap: 8px; }
.head-actions { flex-shrink: 0; }
.bubble { min-width: 0; overflow-wrap: anywhere; }
.md { overflow-x: auto; }
.history-delete { opacity: 1; }

.assistant-overlay {
  position: fixed;
  inset: 0;
  z-index: 80;
  padding: 32px;
  background: rgba(18, 23, 38, 0.42);
  backdrop-filter: blur(3px);
  display: flex;
  align-items: stretch;
  justify-content: center;
}
.assistant-workspace {
  position: relative;
  width: min(1180px, 100%);
  min-width: 0;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--wao-line) 80%, transparent);
  border-radius: 18px;
  background: var(--wao-bg-card);
  box-shadow: 0 24px 80px rgba(15, 20, 35, 0.24);
  display: flex;
}
.history-sidebar {
  width: 270px;
  flex: 0 0 270px;
  min-width: 0;
  padding: 18px 14px 14px;
  border-right: 1px solid var(--wao-line);
  background: color-mix(in srgb, var(--wao-bg-page) 72%, var(--wao-bg-card));
  display: flex;
  flex-direction: column;
}
.history-brand { display: flex; align-items: center; gap: 10px; padding: 0 6px 16px; }
.brand-icon, .welcome-icon {
  background: linear-gradient(135deg, #4358d6, #7658d8);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.brand-icon { width: 36px; height: 36px; border-radius: 10px; font-size: 20px; }
.history-brand b, .history-brand small { display: block; }
.history-brand b { color: var(--wao-text); font-size: 15px; }
.history-brand small { margin-top: 1px; color: var(--wao-text-weak); font-size: 11px; }
.new-chat-btn {
  width: 100%;
  height: 40px;
  border: 1px solid color-mix(in srgb, var(--wao-primary) 35%, var(--wao-line));
  border-radius: 10px;
  background: color-mix(in srgb, var(--wao-primary) 7%, var(--wao-bg-card));
  color: var(--wao-primary-text);
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
}
.new-chat-btn:hover:not(:disabled) { background: color-mix(in srgb, var(--wao-primary) 12%, var(--wao-bg-card)); }
.new-chat-btn:disabled { opacity: .55; cursor: not-allowed; }
.history-search {
  height: 34px;
  margin: 12px 2px 14px;
  padding: 0 10px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
  display: flex;
  align-items: center;
  gap: 7px;
}
.history-search input { width: 100%; border: 0; outline: 0; background: transparent; color: var(--wao-text); font-size: 12px; }
.history-label { padding: 0 7px 8px; color: var(--wao-text-weak); font-size: 11px; display: flex; justify-content: space-between; }
.history-list { min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; }
.history-item {
  width: 100%;
  padding: 9px 8px 9px 10px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 6px;
}
.history-item:hover:not(:disabled), .history-item.active { background: var(--wao-bg-hover-2); }
.history-item.active { box-shadow: inset 2px 0 var(--wao-primary); }
.history-item:disabled { cursor: default; }
.history-copy { min-width: 0; flex: 1; }
.history-copy b, .history-copy small { display: block; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.history-copy b { color: var(--wao-text-2); font-size: 12px; font-weight: 600; }
.history-copy small { margin-top: 3px; color: var(--wao-text-weak); font-size: 10px; }
.history-meta { flex: 0 0 auto; align-self: stretch; display: flex; flex-direction: column; align-items: flex-end; justify-content: space-between; }
.history-meta time { color: var(--wao-text-weak); font-size: 9px; }
.history-delete { opacity: 0; width: 20px; height: 20px; border-radius: 5px; color: var(--wao-text-weak); display: inline-flex; align-items: center; justify-content: center; }
.history-item:hover .history-delete { opacity: 1; }
.history-delete:hover { background: color-mix(in srgb, #e5484d 12%, transparent); color: #e5484d; }
.history-empty { padding: 24px 8px; color: var(--wao-text-weak); font-size: 12px; text-align: center; }
.history-empty button { margin-left: 5px; border: 0; background: transparent; color: var(--wao-primary-text); cursor: pointer; }
.history-empty.error { color: #e5484d; }

.chat-main { min-width: 0; flex: 1; background: var(--wao-bg-card); display: flex; flex-direction: column; }
.chat-head {
  height: 64px;
  padding: 0 18px;
  border-bottom: 1px solid var(--wao-line);
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.chat-heading { min-width: 0; display: flex; align-items: center; gap: 9px; }
.chat-heading b, .chat-heading small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-heading b { max-width: 420px; color: var(--wao-text); font-size: 14px; }
.chat-heading small { max-width: 480px; margin-top: 3px; color: var(--wao-text-weak); font-size: 10px; }
.chat-heading small i { margin-right: 4px; color: #d89614; font-size: 8px; }
.chat-heading small.online i { color: #36a269; }
.head-actions { display: flex; align-items: center; gap: 8px; }
.head-new { padding: 6px 10px; border: 1px solid var(--wao-line); border-radius: 8px; background: transparent; color: var(--wao-text-2); cursor: pointer; font-size: 12px; }
.head-new i { margin-right: 3px; }
.head-new:hover:not(:disabled) { background: var(--wao-bg-hover-2); color: var(--wao-primary-text); }
.icon-btn { width: 32px; height: 32px; border: 0; border-radius: 8px; background: transparent; color: var(--wao-text-3); cursor: pointer; display: inline-flex; align-items: center; justify-content: center; }
.icon-btn:hover { background: var(--wao-bg-hover-2); color: var(--wao-text); }
.sidebar-toggle { display: none; }

.assistant-list { min-height: 0; flex: 1; overflow-y: auto; padding: 20px 28px 8px; }
.message-column, .welcome, .chat-loading { width: min(780px, 100%); margin: 0 auto; }
.assistant-status, .inline-error {
  width: min(780px, 100%);
  margin: 0 auto 12px;
  padding: 9px 11px;
  border-radius: 9px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 7px;
}
.assistant-status { border: 1px solid color-mix(in srgb, #d89614 45%, var(--wao-line)); background: color-mix(in srgb, #d89614 8%, transparent); color: var(--wao-text-2); }
.assistant-status span { flex: 1; }
.assistant-status button { border: 0; background: transparent; color: var(--wao-primary-text); cursor: pointer; }
.inline-error { background: color-mix(in srgb, #e5484d 8%, transparent); color: #c43d42; }
.chat-loading { padding-top: 120px; color: var(--wao-text-weak); text-align: center; font-size: 13px; }
.welcome { min-height: 100%; padding: 90px 0 40px; text-align: center; display: flex; flex-direction: column; align-items: center; }
.welcome-icon { width: 52px; height: 52px; border-radius: 15px; box-shadow: 0 10px 24px rgba(67, 88, 214, .22); font-size: 28px; }
.welcome h2 { margin: 18px 0 7px; color: var(--wao-text); font-size: 23px; }
.welcome p { margin: 0; color: var(--wao-text-weak); font-size: 13px; }
.welcome-actions { width: min(560px, 100%); margin-top: 28px; display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.welcome-actions button { min-height: 48px; padding: 10px 14px; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); color: var(--wao-text-2); cursor: pointer; text-align: left; display: flex; align-items: center; gap: 9px; }
.welcome-actions button:hover:not(:disabled) { border-color: color-mix(in srgb, var(--wao-primary) 45%, var(--wao-line)); background: color-mix(in srgb, var(--wao-primary) 5%, var(--wao-bg-card)); color: var(--wao-primary-text); }
.welcome-actions button:disabled { opacity: .48; cursor: not-allowed; }
.welcome-actions i { font-size: 17px; }
.msg { margin: 0 0 22px; display: flex; align-items: flex-start; gap: 10px; }
.msg.user { justify-content: flex-end; }
.avatar { width: 30px; height: 30px; flex: 0 0 30px; border-radius: 9px; background: linear-gradient(135deg, #4358d6, #7658d8); color: #fff; display: inline-flex; align-items: center; justify-content: center; }
.bubble { max-width: min(680px, 86%); color: var(--wao-text); font-size: 14px; line-height: 1.7; }
.msg.user .bubble { padding: 10px 14px; border-radius: 14px 14px 4px 14px; background: var(--wao-primary); color: #fff; }
.msg.assistant .bubble { padding: 3px 0; }
.user-text { margin: 0; white-space: pre-wrap; word-break: break-word; font: inherit; }
.cursor { display: inline-block; margin-left: 2px; color: var(--wao-primary-text); animation: blink 1s infinite; }
.md :deep(p) { margin: 0 0 9px; }
.md :deep(p:last-child) { margin-bottom: 0; }
.md :deep(ul), .md :deep(ol) { margin: 0 0 9px; padding-left: 20px; }
.md :deep(code) { padding: 1px 5px; border-radius: 4px; background: var(--wao-bg-page); font: 12px 'SF Mono', Menlo, monospace; }
.md :deep(pre) { margin: 8px 0; padding: 12px; overflow-x: auto; border-radius: 8px; background: var(--wao-bg-page); }
.md :deep(pre code) { padding: 0; background: transparent; }
.md :deep(a) { color: var(--wao-primary-text); }

.composer-wrap { position: relative; padding: 10px 28px 20px; background: linear-gradient(to bottom, transparent, var(--wao-bg-card) 20%); }
.assistant-input { width: min(780px, 100%); margin: 0 auto; padding: 10px 12px 9px; border: 1px solid var(--wao-line); border-radius: 16px; background: var(--wao-bg-card); box-shadow: 0 6px 24px rgba(20, 28, 55, .08); }
.assistant-input:focus-within { border-color: color-mix(in srgb, var(--wao-primary) 65%, var(--wao-line)); box-shadow: 0 8px 28px rgba(67, 88, 214, .12); }
.assistant-input.disabled { opacity: .68; }
.assistant-input textarea { width: 100%; min-height: 48px; max-height: 140px; resize: none; border: 0; outline: 0; background: transparent; color: var(--wao-text); font: 13px/1.55 inherit; }
.capability-picker { max-width: 100%; margin-bottom: 4px; color: var(--wao-primary-text); font-size: 10px; display: flex; align-items: center; gap: 5px; }
.capability-picker select { min-width: 0; max-width: 280px; border: 0; outline: 0; background: transparent; color: var(--wao-text-2); font: 10px inherit; cursor: pointer; }
.source-summary { max-width: 100%; margin: -2px 0 5px; padding: 4px 7px; border: 0; border-radius: 7px; background: transparent; color: var(--wao-text-weak); cursor: pointer; font-size: 10px; display: flex; align-items: center; gap: 5px; }
.source-summary:hover, .source-summary.active { background: color-mix(in srgb, var(--wao-primary) 8%, transparent); color: var(--wao-primary-text); }
.source-summary span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.source-summary em { min-width: 16px; height: 16px; padding: 0 4px; border-radius: 8px; background: var(--wao-primary); color: #fff; font-style: normal; font-size: 9px; line-height: 16px; }
.source-filter-panel { width: min(780px, 100%); max-height: min(430px, 48vh); margin: 0 auto 9px; padding: 14px; overflow-y: auto; border: 1px solid var(--wao-line); border-radius: 14px; background: var(--wao-bg-card); box-shadow: 0 14px 40px rgba(20, 28, 55, .15); }
.source-filter-head { margin-bottom: 12px; display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.source-filter-head b, .source-filter-head small { display: block; }
.source-filter-head b { color: var(--wao-text); font-size: 13px; }
.source-filter-head small { margin-top: 2px; color: var(--wao-text-weak); font-size: 10px; }
.source-filter-head button, .source-options-error button { border: 0; background: transparent; color: var(--wao-primary-text); cursor: pointer; font-size: 11px; }
.source-filter-head button:disabled { opacity: .35; cursor: default; }
.source-options-error, .source-options-loading { padding: 16px; color: var(--wao-text-weak); font-size: 11px; text-align: center; }
.source-options-error { color: #c43d42; }
.filter-section { margin-top: 10px; display: grid; grid-template-columns: 78px minmax(0, 1fr); align-items: start; gap: 8px; }
.filter-section > label { padding-top: 6px; color: var(--wao-text-3); font-size: 11px; font-weight: 600; }
.filter-section > small { padding-top: 6px; color: var(--wao-text-weak); font-size: 10px; }
.filter-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.filter-chips.scrollable { max-height: 72px; overflow-y: auto; }
.filter-chips.projects { max-height: 100px; }
.filter-chips button { padding: 5px 9px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-3); cursor: pointer; font-size: 11px; }
.filter-chips button:hover { border-color: color-mix(in srgb, var(--wao-primary) 50%, var(--wao-line)); color: var(--wao-primary-text); }
.filter-chips button.selected { border-color: color-mix(in srgb, var(--wao-primary) 55%, var(--wao-line)); background: color-mix(in srgb, var(--wao-primary) 10%, transparent); color: var(--wao-primary-text); }
.filter-chips button i { margin-right: 4px; }
.date-filter { grid-template-columns: 78px minmax(120px, 1fr) auto minmax(120px, 1fr); align-items: center; }
.date-filter > label { padding-top: 0; }
.date-filter input { min-width: 0; padding: 6px 8px; border: 1px solid var(--wao-line); border-radius: 8px; outline: none; background: var(--wao-bg-card); color: var(--wao-text-2); font: 11px inherit; }
.date-filter input:focus { border-color: var(--wao-primary); }
.date-filter span { color: var(--wao-text-weak); font-size: 10px; }
.composer-foot { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.composer-foot > span { color: var(--wao-text-weak); font-size: 10px; }
.composer-foot > span i { margin-right: 4px; color: #36a269; }
.send-btn { width: 34px; height: 34px; flex: 0 0 34px; border: 0; border-radius: 10px; background: var(--wao-primary); color: #fff; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; }
.send-btn:disabled { opacity: .35; cursor: not-allowed; }
.send-btn i { font-size: 18px; }

.mobile-sidebar-mask { display: none; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@keyframes blink { 50% { opacity: 0; } }
.assistant-enter-active, .assistant-leave-active { transition: opacity .2s ease; }
.assistant-enter-active .assistant-workspace, .assistant-leave-active .assistant-workspace { transition: transform .2s ease, opacity .2s ease; }
.assistant-enter-from, .assistant-leave-to { opacity: 0; }
.assistant-enter-from .assistant-workspace, .assistant-leave-to .assistant-workspace { opacity: 0; transform: translateY(10px) scale(.985); }

@media (max-width: 760px) {
  .assistant-overlay { padding: 0; }
  .assistant-workspace { border: 0; border-radius: 0; }
  .history-sidebar { position: absolute; z-index: 3; inset: 0 auto 0 0; transform: translateX(-100%); transition: transform .2s ease; box-shadow: 12px 0 36px rgba(15,20,35,.18); }
  .history-sidebar.visible { transform: translateX(0); }
  .mobile-sidebar-mask { position: absolute; z-index: 2; inset: 0; display: block; border: 0; background: rgba(15,20,35,.3); }
  .sidebar-toggle { display: inline-flex; }
  .head-new { display: none; }
  .chat-head { padding: 0 10px; }
  .chat-heading b { max-width: 190px; }
  .chat-heading small { max-width: 220px; }
  .assistant-list { padding: 16px 16px 6px; }
  .composer-wrap { padding: 8px 12px 14px; }
  .source-filter-panel { max-height: 52vh; padding: 12px; }
  .filter-section { grid-template-columns: 1fr; gap: 4px; }
  .filter-section > label { padding-top: 0; }
  .date-filter { grid-template-columns: 1fr auto 1fr; }
  .date-filter > label { grid-column: 1 / -1; }
  .welcome { padding-top: 65px; }
  .welcome-actions { grid-template-columns: 1fr; }
  .composer-foot > span { display: none; }
}
</style>
