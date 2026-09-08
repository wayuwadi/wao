<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import type { AiRun, AiRunProgress } from '@/types'
import { sessionConversation, sendSessionInput } from '@/api/aiSessions'
import { useSessionStore } from '@/stores/session'
import { renderMarkdown } from '@/utils/markdown'
import { formatTokens } from '@/composables/aiUsage'
import AiRunDeliverySteps from './AiRunDeliverySteps.vue'
const props = defineProps<{ run: AiRun; prompt: string | null }>()
const emit = defineEmits<{ updated: [run: AiRun] }>()
const account = useSessionStore()
const draftKey = computed(() => `wao:airun-draft:${account.userId}:${props.run.id}`)
const draft = ref(''),
  draftError = ref(''),
  sendError = ref(''),
  sending = ref(false)
const history = ref<AiRunProgress[]>([]),
  loadingHistory = ref(false),
  historyError = ref(''),
  exhausted = ref(false)
const scroll = ref<HTMLElement | null>(null),
  nearBottom = ref(true)
const active = computed(() => ['queued', 'claimed', 'running'].includes(props.run.status))
const canSend = computed(() => active.value || !!(props.run.sessionId && props.run.runnerId))
const items = computed(() =>
  [...new Map([...history.value, ...props.run.progress].map((x) => [x.id, x])).values()].sort(
    (a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id),
  ),
)
const isUser = (x: AiRunProgress) =>
  x.kind === 'user' || /^(用户在执行中补充：|用户要求继续优化：|用户补充：)/.test(x.content)
const messages = computed(() => items.value.filter((x) => x.kind === 'message' || isUser(x)))
const events = computed(() => items.value.filter((x) => x.kind !== 'message' && !isUser(x)))
watch(
  draftKey,
  () => {
    try {
      draft.value = localStorage.getItem(draftKey.value) || ''
      draftError.value = ''
    } catch {
      draftError.value = '无法读取本机草稿'
    }
  },
  { immediate: true },
)
function input(value: string) {
  draft.value = value
  try {
    value ? localStorage.setItem(draftKey.value, value) : localStorage.removeItem(draftKey.value)
    draftError.value = ''
  } catch {
    draftError.value = '本机草稿保存失败，请保留页面'
  }
}
watch(
  () => props.run.progress,
  (value, previous) => {
    // 保存轮询窗口滑出之前的消息，避免阅读中的内容每 50 条被截断。
    if (previous)
      history.value = [...new Map([...history.value, ...previous].map((x) => [x.id, x])).values()]
    if (nearBottom.value)
      void nextTick(() => {
        if (scroll.value) scroll.value.scrollTop = scroll.value.scrollHeight
      })
  },
  { immediate: true },
)
function onScroll() {
  const el = scroll.value
  if (el) nearBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 100
}
async function earlier() {
  loadingHistory.value = true
  historyError.value = ''
  const top = scroll.value?.scrollHeight || 0
  try {
    const page = await sessionConversation(props.run.id, items.value[0]?.id)
    history.value = [...page.items, ...items.value]
    exhausted.value = !page.nextCursor
    await nextTick()
    if (scroll.value) scroll.value.scrollTop += scroll.value.scrollHeight - top
  } catch (e) {
    historyError.value = e instanceof Error ? e.message : '读取失败，请重试'
  } finally {
    loadingHistory.value = false
  }
}
async function send() {
  const value = draft.value.trim(),
    key = draftKey.value
  if (!value || sending.value || !canSend.value || props.run.continuationPending) return
  sending.value = true
  sendError.value = ''
  try {
    const updated = await sendSessionInput(props.run.id, value)
    draft.value = ''
    nearBottom.value = true
    emit('updated', updated)
    try {
      localStorage.removeItem(key)
    } catch {
      draftError.value = '已发送，但本机草稿清理失败'
    }
  } catch (e) {
    sendError.value = e instanceof Error ? e.message : '发送失败，草稿已保留，请重试'
  } finally {
    sending.value = false
  }
}
const time = (s: string) => new Date(s).toLocaleString('zh-CN', { hour12: false })
</script>

<template>
  <div class="session-conversation">
    <div ref="scroll" class="message-scroll" @scroll="onScroll">
      <details class="initial-request">
        <summary>初始任务要求</summary>
        <div
          class="markdown"
          v-html="renderMarkdown(prompt || run.taskTitle || '暂无任务描述')"
        ></div>
      </details>
      <button
        v-if="!exhausted && items.length >= 50"
        class="earlier"
        :disabled="loadingHistory"
        @click="earlier"
      >
        {{ loadingHistory ? '正在读取…' : historyError || '加载更早的对话与过程' }}
      </button>
      <p v-if="!messages.length" class="empty">
        {{
          active
            ? '任务已派发，等待 AI 的第一条回复…'
            : '此历史运行没有保存对话消息，可展开执行记录或查看结果。'
        }}
      </p>
      <article
        v-for="message in messages"
        :key="message.id"
        class="message"
        :class="{ user: isUser(message) }"
      >
        <div class="message-label">
          <b>{{ isUser(message) ? '你' : 'AI' }}</b
          ><time>{{ time(message.createdAt) }}</time>
        </div>
        <div class="markdown" v-html="renderMarkdown(message.content)"></div>
      </article>
      <details class="execution">
        <summary>
          执行过程 · {{ events.length }} 条记录
          <span>{{ run.runnerName || '等待分配执行电脑' }}</span>
        </summary>
        <AiRunDeliverySteps v-if="run.deliveryStage" :stage="run.deliveryStage" />
        <p>
          {{ run.requestedModel || run.engine }} ·
          {{ run.usage ? formatTokens(run.usage.totalTokens) + ' Token' : '暂无消耗记录' }}
        </p>
        <ol>
          <li v-for="event in events" :key="event.id">
            <time>{{ time(event.createdAt) }}</time>
            <p>{{ event.content }}</p>
          </li>
        </ol>
        <details v-if="run.resultSummary">
          <summary>本轮最终结果</summary>
          <pre>{{ run.resultSummary }}</pre>
        </details>
        <a v-if="run.reportUrl" :href="run.reportUrl" target="_blank" rel="noopener noreferrer"
          >打开验收报告 ↗</a
        >
      </details>
      <p v-if="active" class="live-status" role="status">
        <i class="ri-loader-4-line"></i> {{ run.latestProgress || 'AI 正在执行' }}
      </p>
    </div>
    <form class="composer" @submit.prevent="send">
      <div v-if="!canSend" class="empty">
        本次运行尚未保存可恢复的会话。可回看历史，或新建会话继续处理。
      </div>
      <template v-else>
        <textarea
          :value="draft"
          :readonly="sending"
          :disabled="run.continuationPending"
          maxlength="4000"
          aria-label="继续对话"
          placeholder="继续说你的想法，AI 会保留这次上下文…"
          @input="input(($event.target as HTMLTextAreaElement).value)"
          @keydown.enter.ctrl.prevent="send"
          @keydown.enter.meta.prevent="send"
        ></textarea>
        <div class="composer-footer">
          <small role="status">{{
            sendError ||
            draftError ||
            (run.continuationPending
              ? '输入已保存，等待原执行电脑接收'
              : draft
                ? '草稿已保存在本机 · Ctrl/⌘ + Enter 发送'
                : '在原会话继续，不必重复说明背景')
          }}</small>
          <button type="submit" :disabled="sending || run.continuationPending || !draft.trim()">
            {{
              sending ? '发送中…' : sendError ? '重试发送' : active ? '发送到当前会话' : '继续对话'
            }}
            <i class="ri-send-plane-2-line"></i>
          </button>
        </div>
      </template>
    </form>
  </div>
</template>
<style scoped>
.session-conversation {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
}
.message-scroll {
  overflow: auto;
  flex: 1;
  padding: 24px;
  scrollbar-gutter: stable;
}
.initial-request,
.execution {
  padding: 12px 16px;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  color: var(--wao-text-3);
  font-size: 12px;
  margin-bottom: 20px;
}
.initial-request summary,
.execution summary {
  cursor: pointer;
}
.execution span {
  float: right;
}
.execution ol {
  padding-left: 18px;
}
.execution li {
  margin-top: 12px;
}
.execution p,
.execution pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.execution time {
  font-size: 11px;
}
.message {
  max-width: 880px;
  margin: 0 auto 24px;
  padding: 16px 20px;
  border-radius: 14px;
  background: var(--wao-bg-card);
  border: 1px solid var(--wao-line);
}
.message.user {
  background: var(--wao-primary-soft);
  margin-left: 8%;
}
.message-label {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  color: var(--wao-text-3);
  margin-bottom: 12px;
}
.message-label b {
  color: var(--wao-primary);
}
.markdown {
  font-size: 14px;
  line-height: 1.8;
  overflow-wrap: anywhere;
  color: var(--wao-text);
}
.markdown :deep(pre) {
  overflow: auto;
  background: var(--wao-bg-sub);
  padding: 12px;
  border-radius: 8px;
}
.markdown :deep(img) {
  max-width: 100%;
}
.markdown :deep(table) {
  display: block;
  overflow: auto;
}
.markdown :deep(a) {
  color: var(--wao-primary);
}
.composer {
  border-top: 1px solid var(--wao-line);
  padding: 14px 20px;
  background: var(--wao-bg-card);
}
textarea {
  box-sizing: border-box;
  width: 100%;
  min-height: 90px;
  max-height: 220px;
  resize: vertical;
  border: 1px solid var(--wao-line-control);
  border-radius: 10px;
  background: var(--wao-bg-sub);
  color: var(--wao-text);
  padding: 12px;
  font: inherit;
  font-size: 14px;
}
.composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
}
.composer-footer small {
  color: var(--wao-text-3);
  font-size: 11px;
}
.composer button {
  border: 0;
  border-radius: 8px;
  background: var(--wao-primary);
  color: #fff;
  padding: 10px 16px;
  white-space: nowrap;
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
.empty,
.live-status {
  font-size: 12px;
  color: var(--wao-text-3);
  overflow-wrap: anywhere;
}
.earlier {
  display: block;
  margin: 12px auto;
  border: 1px solid var(--wao-line);
  background: var(--wao-bg-card);
  color: var(--wao-primary);
  border-radius: 8px;
  padding: 8px 16px;
  cursor: pointer;
}
@media (max-width: 700px) {
  .message-scroll {
    padding: 12px;
  }
  .message {
    padding: 12px;
  }
  .composer {
    padding: 10px;
  }
  .composer-footer {
    align-items: flex-start;
  }
  .execution span {
    float: none;
    display: block;
  }
  .message-label {
    flex-wrap: wrap;
  }
  .composer-footer small {
    max-width: 55%;
  }
}
</style>
