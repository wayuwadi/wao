<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import PetSprite from '@/components/assistant/PetSprite.vue'
import PetMessenger from '@/components/chat/PetMessenger.vue'
import ChatView from '@/views/ChatView.vue'
import { getSession } from '@/api/auth'
import { isAxiosError } from 'axios'

const AiAssistantPanel = defineAsyncComponent(
  () => import('@/components/assistant/AiAssistantPanel.vue'),
)
const ui = useUiStore()
const session = useSessionStore()
const aiOpen = ref(false)
const quickReplyOpen = ref(false)
const native = new URLSearchParams(location.search).get('native') === '1'
const mode = computed(() =>
  !session.authed
    ? 'bubble'
    : ui.chatOpen || aiOpen.value
      ? 'chat'
      : ui.chatUnreadCount || quickReplyOpen.value
        ? 'bubble'
        : 'compact',
)
function nativeAction(action: 'compact' | 'bubble' | 'chat' | 'hide' | 'workspace' | 'drag') {
  if (native) location.href = `/__desktop_pet__/${action}`
  else if (action === 'workspace') location.href = '/'
}
watch(mode, (value) => nativeAction(value))
function openChat() {
  aiOpen.value = false
  ui.openChat()
}
function openAi() {
  ui.closeChat()
  aiOpen.value = true
}
function escape(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    ui.closeChat()
    aiOpen.value = false
  }
}
let sessionTimer: ReturnType<typeof setInterval> | undefined
async function refreshSession() {
  try {
    const account = await getSession()
    if (account.member.id !== session.userId) {
      ui.closeChat()
      aiOpen.value = false
      session.accept(account)
    }
  } catch (error) {
    if (
      (isAxiosError(error) && error.response?.status === 401) ||
      (error instanceof Error && 'status' in error && error.status === 401)
    ) {
      session.account = null
      ui.chatLatestUnread = null
      ui.chatUnreadCount = 0
      ui.closeChat()
      aiOpen.value = false
    }
  }
}
onMounted(() => {
  sessionTimer = setInterval(refreshSession, 30_000)
  window.addEventListener('focus', refreshSession)
  document.documentElement.classList.add('desktop-pet-surface')
  window.addEventListener('keydown', escape)
  nativeAction(mode.value)
})
onBeforeUnmount(() => {
  clearInterval(sessionTimer)
  window.removeEventListener('focus', refreshSession)
  document.documentElement.classList.remove('desktop-pet-surface')
  window.removeEventListener('keydown', escape)
})
</script>

<template>
  <main class="desktop-pet" :class="mode">
    <section v-if="!session.authed" class="pet-login">
      <p>先登录 WAO，我就能帮你传话。</p>
      <button @click="nativeAction('workspace')">打开 WAO 登录</button>
    </section>
    <template v-else :key="session.userId">
      <div v-show="ui.chatOpen" class="pet-chat-panel">
        <ChatView
          floating
          draft-scope="desktop"
          :visible="ui.chatOpen"
          @minimize="ui.closeChat()"
        />
      </div>
      <section v-if="aiOpen" class="pet-ai-panel">
        <header>和宠物聊 · AI<button @click="aiOpen = false">收起</button></header>
        <div><AiAssistantPanel title="和宠物聊 · AI" :model-value="true" embedded /></div>
      </section>
      <PetMessenger v-if="!aiOpen" @expanded="quickReplyOpen = $event" />
    </template>
    <footer class="pet-dock">
      <button
        class="drag-handle"
        title="拖动宠物"
        aria-label="拖动宠物"
        @pointerdown="nativeAction('drag')"
      >
        <i class="ri-draggable"></i>
      </button>
      <button
        class="pet-avatar"
        title="打开团队聊天"
        aria-label="宠物传话：打开团队聊天"
        @click="openChat"
      >
        <PetSprite :state="ui.chatUnreadCount ? 'waving' : 'idle'" /><b v-if="ui.chatUnreadCount">{{
          ui.chatUnreadCount
        }}</b>
      </button>
      <nav>
        <button title="团队聊天" aria-label="团队聊天" @click="openChat">
          <i class="ri-chat-3-line"></i></button
        ><button title="和宠物聊 · AI" aria-label="和宠物聊 · AI" @click="openAi">
          <i class="ri-sparkling-line"></i></button
        ><button
          title="隐藏宠物，可从客户端恢复"
          aria-label="隐藏宠物"
          @click="nativeAction('hide')"
        >
          <i class="ri-subtract-line"></i>
        </button>
      </nav>
    </footer>
  </main>
</template>

<style>
html.desktop-pet-surface,
html.desktop-pet-surface body,
html.desktop-pet-surface #app {
  background: transparent !important;
  min-width: 0 !important;
  overflow: hidden;
}
</style>
<style scoped>
.desktop-pet {
  height: 100dvh;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: flex-end;
  padding: 8px;
  gap: 8px;
  overflow: hidden;
}
.pet-dock {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-shrink: 0;
}
.pet-dock button {
  border: 0;
  cursor: pointer;
  color: var(--wao-text-2);
  background: var(--wao-bg-card);
  border-radius: 12px;
  padding: 6px;
}
.pet-dock .pet-avatar {
  position: relative;
  background: transparent;
  padding: 0;
}
.pet-avatar b {
  position: absolute;
  right: 0;
  top: 0;
  background: var(--wao-danger);
  color: white;
  border-radius: 12px;
  padding: 0 5px;
  font-size: 11px;
}
.pet-dock nav {
  display: grid;
  gap: 3px;
}
.drag-handle {
  cursor: grab !important;
}
.pet-chat-panel,
.pet-ai-panel {
  width: 100%;
  flex: 1;
  min-height: 0;
  background: var(--wao-bg-card);
  border-radius: 18px;
  overflow: hidden;
  border: 1px solid var(--wao-line);
}
.pet-chat-panel :deep(.chat-page) {
  height: 100%;
}
.pet-ai-panel {
  display: flex;
  flex-direction: column;
}
.pet-ai-panel > header {
  padding: 10px;
  display: flex;
  justify-content: space-between;
}
.pet-ai-panel > div {
  position: relative;
  flex: 1;
  min-height: 0;
}
.pet-login {
  background: var(--wao-bg-card);
  padding: 12px;
  border-radius: 14px;
  font-size: 12px;
}
</style>
