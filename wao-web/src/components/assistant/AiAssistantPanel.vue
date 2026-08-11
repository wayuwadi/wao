<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { fetchAssistantStatus, streamChat, type AssistantStatus, type ChatMessage } from '@/api/assistant'
import { marked } from 'marked'
import DOMPurify from 'dompurify'

interface Msg {
  role: 'user' | 'assistant'
  content: string
  /** 正在流式接收时为 true。 */
  streaming?: boolean
}

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const router = useRouter()
const ui = useUiStore()

const open = ref(false)
const messages = ref<Msg[]>([])
const input = ref('')
const sending = ref(false)
const statusLoading = ref(false)
const assistantStatus = ref<AssistantStatus>({ configured: false, message: '正在检查本地 Codex…' })
const listRef = ref<HTMLElement | null>(null)
let statusRefreshTimer: ReturnType<typeof setInterval> | null = null

const QUICK_COMMANDS = [
  { id: 'summary', title: '总结当前页面', icon: 'ri-file-text-line',
    build: () => ({ message: '请用一段话总结当前页面的核心内容和关键数据。', context: capturePageContext() }) },
  { id: 'task', title: '创建新任务', icon: 'ri-checkbox-circle-line',
    build: () => ({ message: '我想创建一条新任务，请帮我建议一个清晰的标题和描述。', context: undefined }) },
  { id: 'project', title: '创建新项目', icon: 'ri-stack-line',
    build: () => ({ message: '我想创建一个新的项目，请帮我建议项目名称、目标和关键里程碑。', context: undefined }) },
  { id: 'error', title: '解释错误', icon: 'ri-bug-line',
    build: () => ({ message: '请帮我解释最近遇到的错误信息，并给出排查思路。', context: capturePageContext() }) },
]

function close() { emit('update:modelValue', false) }

function capturePageContext(): string {
  const route = router.currentRoute.value
  const name = String(route.meta.module || route.name || '未知页面')
  const path = route.fullPath
  // 简单抓取当前页面可见文本的前 2000 字符，作为上下文。
  const body = (document.querySelector('.page-body') || document.body) as HTMLElement
  const text = body?.innerText?.slice(0, 2000) || ''
  return `当前页面：${name} (${path})\n\n页面内容摘要：\n${text}`
}

function scrollToBottom() {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

async function loadStatus() {
  if (statusLoading.value) return
  statusLoading.value = true
  try {
    assistantStatus.value = await fetchAssistantStatus()
  } catch {
    assistantStatus.value = { configured: false, message: '无法读取 AI 状态，请稍后重试' }
  } finally {
    statusLoading.value = false
  }
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

async function send(text?: string, context?: string) {
  const msg = (text ?? input.value).trim()
  if (!msg || sending.value || !assistantStatus.value.configured) return
  sending.value = true
  messages.value.push({ role: 'user', content: msg })
  input.value = ''
  scrollToBottom()

  const assistantMsg: Msg = { role: 'assistant', content: '', streaming: true }
  messages.value.push(assistantMsg)
  scrollToBottom()

  try {
    const history: ChatMessage[] = messages.value
      .filter((m) => m.role !== 'assistant' || !m.streaming)
      .slice(0, -1)
      .slice(-10)
      .map((m) => ({ role: m.role, content: m.content }))

    for await (const token of streamChat(msg, history, context)) {
      assistantMsg.content += token
      scrollToBottom()
    }
    assistantMsg.streaming = false
  } catch (error) {
    assistantMsg.content = error instanceof Error ? error.message : '请求失败'
    assistantMsg.streaming = false
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

function quick(cmd: typeof QUICK_COMMANDS[number]) {
  const request = cmd.build()
  send(request.message, request.context)
}

function clearHistory() {
  messages.value = []
}

watch(() => props.modelValue, (v) => {
  open.value = v
  if (v) {
    void loadStatus()
    startStatusRefresh()
  } else {
    stopStatusRefresh()
  }
})
watch(open, (v) => { if (!v) emit('update:modelValue', false) })

onMounted(() => {
  void loadStatus()
  if (messages.value.length === 0) {
    messages.value.push({
      role: 'assistant',
      content: '你好！我是 WAO 智能助手。你可以问我关于当前页面的问题，或者使用下方的快捷指令。\n\n**我能帮你做什么：**\n- 总结当前页面的内容\n- 创建新任务或新项目\n- 解释错误信息\n- 回答关于 WAO 使用的问题',
    })
  }
})

onBeforeUnmount(stopStatusRefresh)

function renderMarkdown(text: string): string {
  if (!text) return ''
  const html = marked.parse(text, { async: false }) as string
  return DOMPurify.sanitize(html)
}
</script>

<template>
  <Teleport to="body">
    <Transition name="assistant">
      <div v-if="open" class="assistant-overlay" @click.self="close">
        <aside class="assistant">
          <header class="assistant-head">
            <div class="assistant-title">
              <i class="ri-robot-2-line"></i>
              <div>
                <b>AI 助手</b>
                <small>基于当前页面上下文回答</small>
              </div>
            </div>
            <div class="assistant-actions">
              <button class="icon-btn" title="清空对话" @click="clearHistory">
                <i class="ri-delete-bin-line"></i>
              </button>
              <button class="icon-btn" title="关闭" @click="close">
                <i class="ri-close-line"></i>
              </button>
            </div>
          </header>

          <div ref="listRef" class="assistant-list">
            <div v-if="!assistantStatus.configured" class="assistant-status">
              <i :class="statusLoading ? 'ri-loader-4-line spin' : 'ri-error-warning-line'"></i>
              <span>{{ assistantStatus.message }}</span>
              <button :disabled="statusLoading" @click="loadStatus">重试</button>
            </div>
            <div
              v-for="(m, i) in messages"
              :key="i"
              class="msg"
              :class="m.role"
            >
              <div v-if="m.role === 'assistant'" class="avatar"><i class="ri-robot-2-line"></i></div>
              <div class="bubble">
                <div v-if="m.role === 'assistant'" class="md" v-html="renderMarkdown(m.content)"></div>
                <pre v-else class="user-text">{{ m.content }}</pre>
                <span v-if="m.streaming" class="cursor">▊</span>
              </div>
            </div>
          </div>

          <div class="quick-row">
            <button
              v-for="cmd in QUICK_COMMANDS"
              :key="cmd.id"
              class="quick-btn"
              :disabled="sending || !assistantStatus.configured"
              @click="quick(cmd)"
            >
              <i :class="cmd.icon"></i>{{ cmd.title }}
            </button>
          </div>

          <div class="assistant-input">
            <textarea
              v-model="input"
              placeholder="输入问题… (Shift+Enter 换行，Enter 发送)"
              rows="2"
              :disabled="sending || !assistantStatus.configured"
              @keydown.enter.exact.prevent="send()"
            ></textarea>
            <button class="send-btn" :disabled="!input.trim() || sending || !assistantStatus.configured" @click="send()">
              <i :class="sending ? 'ri-loader-4-line spin' : 'ri-send-plane-2-fill'"></i>
            </button>
          </div>
        </aside>
      </div>
    </Transition>

    <button
      v-if="!open"
      class="assistant-fab"
      title="AI 助手"
      @click="emit('update:modelValue', true)"
    >
      <i class="ri-robot-2-line"></i>
    </button>
  </Teleport>
</template>

<style scoped>
.assistant-overlay {
  position: fixed;
  inset: 0;
  z-index: 80;
  background: rgba(15, 20, 35, 0.35);
  display: flex;
  justify-content: flex-end;
  align-items: stretch;
}
.assistant {
  width: 420px;
  max-width: 100vw;
  background: var(--wao-bg-card);
  border-left: 1px solid var(--wao-line);
  display: flex;
  flex-direction: column;
  box-shadow: -10px 0 40px rgba(0, 0, 0, 0.15);
}
.assistant-head {
  padding: 14px 16px;
  border-bottom: 1px solid var(--wao-line);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.assistant-title {
  display: flex;
  gap: 10px;
  align-items: center;
}
.assistant-title > i {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: linear-gradient(135deg, #4358d6, #7a5cc6);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
}
.assistant-title b { display: block; font-size: 14px; color: var(--wao-text); }
.assistant-title small { font-size: 11px; color: var(--wao-text-weak); }
.assistant-actions { display: flex; gap: 4px; }
.icon-btn {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  border-radius: 6px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.icon-btn:hover { background: var(--wao-bg-hover-2); color: var(--wao-text-2); }

.assistant-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.assistant-status {
  border: 1px solid var(--wao-warning, #d89614);
  background: color-mix(in srgb, var(--wao-warning, #d89614) 10%, transparent);
  border-radius: 8px;
  padding: 9px 10px;
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--wao-text-2);
  font-size: 12px;
}
.assistant-status span { flex: 1; }
.assistant-status button {
  border: 0;
  background: transparent;
  color: var(--wao-primary);
  cursor: pointer;
}
.msg {
  display: flex;
  gap: 8px;
  align-items: flex-start;
}
.msg.user { justify-content: flex-end; }
.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--wao-bg-hover-2);
  color: var(--wao-primary);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.bubble {
  max-width: 78%;
  padding: 10px 12px;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.55;
}
.msg.user .bubble {
  background: var(--wao-primary);
  color: #fff;
  border-bottom-right-radius: 4px;
}
.msg.assistant .bubble {
  background: var(--wao-bg-hover-2);
  color: var(--wao-text);
  border-bottom-left-radius: 4px;
}
.user-text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
}
.cursor {
  display: inline-block;
  animation: blink 1s infinite;
  color: var(--wao-primary);
  margin-left: 2px;
}
@keyframes blink { 50% { opacity: 0; } }

.md :deep(p) { margin: 0 0 8px; }
.md :deep(p:last-child) { margin-bottom: 0; }
.md :deep(ul), .md :deep(ol) { padding-left: 18px; margin: 0 0 8px; }
.md :deep(code) {
  background: var(--wao-bg-page);
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 12px;
  font-family: 'SF Mono', Menlo, monospace;
}
.md :deep(pre) {
  background: var(--wao-bg-page);
  padding: 10px;
  border-radius: 6px;
  overflow-x: auto;
  font-size: 12px;
  margin: 6px 0;
}
.md :deep(pre code) { background: transparent; padding: 0; }
.md :deep(strong) { color: var(--wao-text); }
.md :deep(a) { color: var(--wao-primary); }

.quick-row {
  padding: 8px 12px;
  border-top: 1px solid var(--wao-line);
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.quick-btn {
  padding: 5px 10px;
  border: 1px solid var(--wao-line);
  background: transparent;
  border-radius: 999px;
  font-size: 11px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.quick-btn:hover:not(:disabled) { background: var(--wao-bg-hover-2); color: var(--wao-primary); }
.quick-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.quick-btn i { font-size: 13px; }

.assistant-input {
  padding: 12px 14px;
  border-top: 1px solid var(--wao-line);
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.assistant-input textarea {
  flex: 1;
  resize: none;
  padding: 8px 10px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-page);
  color: var(--wao-text);
  font-size: 13px;
  font-family: inherit;
  line-height: 1.5;
}
.assistant-input textarea:focus { outline: none; border-color: var(--wao-primary); }
.send-btn {
  width: 36px;
  height: 36px;
  border: none;
  background: var(--wao-primary);
  color: #fff;
  border-radius: 8px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.send-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.send-btn i { font-size: 16px; }
.send-btn .spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.assistant-fab {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 70;
  width: 52px;
  height: 52px;
  border: none;
  border-radius: 50%;
  background: linear-gradient(135deg, #4358d6, #7a5cc6);
  color: #fff;
  box-shadow: 0 8px 24px rgba(67, 88, 214, 0.4);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.15s;
}
.assistant-fab:hover { transform: scale(1.08); }
.assistant-fab i { font-size: 24px; }

.assistant-enter-active, .assistant-leave-active { transition: opacity 0.2s; }
.assistant-enter-active .assistant, .assistant-leave-active .assistant { transition: transform 0.2s ease; }
.assistant-enter-from, .assistant-leave-to { opacity: 0; }
.assistant-enter-from .assistant, .assistant-leave-to .assistant { transform: translateX(100%); }

@media (max-width: 600px) {
  .assistant { width: 100vw; }
  .assistant-fab { right: 16px; bottom: 80px; }
}
</style>
