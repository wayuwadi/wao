<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import { sendSharedChatMessage, type SharedChatMessage } from '@/api/chat'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'

const emit = defineEmits<{ expanded: [value: boolean] }>()
const ui = useUiStore()
const session = useSessionStore()
const expanded = ref(false)
watch(expanded, (value) => emit('expanded', value))
const key = computed(() => `wao-pet-reply:${session.userId || 'unknown'}`)
const draft = ref(localStorage.getItem(key.value) || '')
const sending = ref(false)
const error = ref('')
let attempt: { content: string; replyToId: string; token: string } | null = null
const replyTarget = shallowRef<SharedChatMessage | null>(null)
const latest = computed(() =>
  expanded.value && replyTarget.value ? replyTarget.value : ui.chatLatestUnread,
)
function expand() {
  if (!expanded.value) replyTarget.value = ui.chatLatestUnread
  expanded.value = !expanded.value
}
const preview = computed(
  () => latest.value?.content.replace(/!\[[^\]]*\]\([^)]*\)/g, '[图片]').slice(0, 100) || '[图片]',
)
watch(draft, (value) =>
  value ? localStorage.setItem(key.value, value) : localStorage.removeItem(key.value),
)

async function reply() {
  if (
    sending.value ||
    !draft.value.trim() ||
    !latest.value ||
    Array.from(draft.value).length > 2000
  )
    return
  const content = draft.value
  const replyToId = latest.value.id
  if (!attempt || attempt.content !== content || attempt.replyToId !== replyToId) {
    attempt = { content, replyToId, token: crypto.randomUUID().replace(/-/g, '') }
  }
  sending.value = true
  error.value = ''
  try {
    await sendSharedChatMessage({ content, replyToId, clientToken: attempt.token })
    if (draft.value === content) {
      draft.value = ''
      expanded.value = false
    }
    attempt = null
    ui.chatRefreshVersion += 1
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '回复失败，草稿已保留'
  } finally {
    sending.value = false
  }
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    void reply()
  }
}
</script>

<template>
  <section
    v-if="latest && (ui.chatUnreadCount || expanded) && !ui.chatOpen"
    class="pet-messenger"
    @pointerdown.stop
  >
    <button class="message-peek" type="button" @click="expand" :aria-expanded="expanded">
      <span class="pet-voice"
        >我帮你带来一条消息 <b>{{ ui.chatUnreadCount }}</b></span
      >
      <strong>{{ latest.author.name }}</strong
      ><span>{{ preview }}</span>
      <small>点我快速回复</small>
    </button>
    <form v-if="expanded" @submit.prevent="reply">
      <label
        >回复 {{ latest.author.name }} 到团队聊天
        <textarea
          v-model="draft"
          aria-label="宠物快速回复"
          placeholder="说一句，我帮你传达…"
          @keydown="keydown"
        />
      </label>
      <p v-if="error" role="alert">{{ error }}</p>
      <footer>
        <button type="button" @click="ui.openChat()">完整聊天</button>
        <button
          type="submit"
          :disabled="sending || !draft.trim() || Array.from(draft).length > 2000"
        >
          {{ sending ? '传话中…' : error ? '重试发送' : '帮我传话' }}
        </button>
      </footer>
    </form>
  </section>
</template>

<style scoped>
.pet-messenger {
  width: min(300px, calc(100vw - 32px));
  border: 1px solid var(--wao-line);
  border-radius: 18px 18px 5px 18px;
  background: var(--wao-bg-card);
  box-shadow: 0 10px 36px rgba(20, 25, 40, 0.18);
  margin-bottom: 10px;
  color: var(--wao-text-2);
}
.message-peek {
  display: grid;
  gap: 5px;
  width: 100%;
  padding: 14px;
  text-align: left;
  border: 0;
  background: none;
  color: inherit;
  cursor: pointer;
}
.message-peek > span:not(.pet-voice) {
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  font-size: 12px;
  max-height: 70px;
  overflow: hidden;
}
.pet-voice {
  font-size: 11px;
  color: var(--wao-primary-text);
  display: flex;
  justify-content: space-between;
}
.pet-voice b {
  border-radius: 12px;
  background: var(--wao-danger);
  color: white;
  padding: 0 6px;
}
small {
  font-size: 10px;
  color: var(--wao-text-weak);
}
form {
  padding: 0 12px 12px;
}
label {
  font-size: 11px;
}
textarea {
  display: block;
  box-sizing: border-box;
  width: 100%;
  min-height: 65px;
  margin-top: 6px;
  resize: vertical;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  padding: 8px;
  color: inherit;
  background: var(--wao-bg-sub);
}
footer {
  display: flex;
  justify-content: space-between;
  margin-top: 7px;
}
footer button {
  border: 0;
  border-radius: 8px;
  padding: 6px 10px;
  color: var(--wao-primary-text);
  background: var(--wao-primary-soft);
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
p {
  font-size: 11px;
  color: var(--wao-danger);
}
</style>
