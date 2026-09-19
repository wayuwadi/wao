<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import PetSprite from '@/components/assistant/PetSprite.vue'
import PetMessenger from '@/components/chat/PetMessenger.vue'
import { fetchSharedChatMessages } from '@/api/chat'
import { nextPetTaskNotice } from '@/utils/petTaskNotice'
import { fetchTasks } from '@/api/board'
import { getSession } from '@/api/auth'
import { createVisibilityPoller } from '@/composables/visibilityPolling'
const ui = useUiStore()
const session = useSessionStore()
const nativeVersion = new URLSearchParams(location.search).get('native')
const native = ['1', '2', '3'].includes(nativeVersion ?? '')
const noticeVisible = ref(false)
const taskNotice = ref<{ id: string; label: string; status: string } | null>(null)
let taskNoticeTimer: ReturnType<typeof setTimeout> | undefined
let tasksReadAt = 0
let disposed = false
let pointerStart: { x: number; y: number } | null = null
let dragged = false
function nativeAction(
  action: 'compact' | 'bubble' | 'open-chat' | 'workspace' | 'toggle-workspace' | 'drag' | 'hide' | `task/${string}`,
) {
  if (nativeVersion === '1' && (action === 'open-chat' || action.startsWith('task/'))) {
    try {
      localStorage.setItem(
        'wao-desktop-open-request',
        JSON.stringify({ action, member: session.userId, time: Date.now() }),
      )
    } catch {
      /* Open the workspace for manual navigation. */
    }
    location.href = '/__desktop_pet__/workspace'
    return
  }
  if (native) location.href = `/__desktop_pet__/${action}`
  else if (action === 'open-chat') location.href = '/?chat=1'
  else if (action === 'workspace') location.href = '/'
}
function resize(visible: boolean) {
  noticeVisible.value = visible
  nativeAction(visible || taskNotice.value || !session.authed ? 'bubble' : 'compact')
}
function clearTaskNotice() {
  clearTimeout(taskNoticeTimer)
  taskNotice.value = null
  if (!disposed) resize(noticeVisible.value)
}
function openTaskNotice() {
  const id = taskNotice.value?.id
  // 原计时器负责收紧窗口；此事件只发一次原生导航，避免覆盖任务跳转。
  taskNotice.value = null
  if (id) nativeAction(`task/${encodeURIComponent(id)}`)
}
function pointerDown(event: PointerEvent) {
  if (event.button !== 0) return
  pointerStart = { x: event.clientX, y: event.clientY }
  dragged = false
}
function pointerMove(event: PointerEvent) {
  if (!pointerStart || dragged || !event.buttons) return
  if (Math.hypot(event.clientX - pointerStart.x, event.clientY - pointerStart.y) > 5) {
    dragged = true
    nativeAction('drag')
  }
}
function toggleWorkspace() {
  pointerStart = null
  if (!dragged) nativeAction(nativeVersion === '3' ? 'toggle-workspace' : 'workspace')
}
const poller = createVisibilityPoller(
  async () => {
    try {
      const account = await getSession()
      if (disposed) return
      const memberChanged = session.userId !== account.member.id
      if (memberChanged) {
        tasksReadAt = 0
        ui.chatUnreadCount = 0
        ui.chatLatestUnread = null
        clearTaskNotice()
      }
      session.accept(account)
      if (memberChanged) resize(noticeVisible.value)
      const messages = await fetchSharedChatMessages()
      if (disposed || session.userId !== account.member.id) return
      const unread = messages.filter((message) => !message.read)
      ui.chatUnreadCount = unread.length
      ui.chatLatestUnread = unread[unread.length - 1] ?? null
      if (Date.now() - tasksReadAt > 60_000) {
        const tasks = await fetchTasks()
        if (disposed || session.userId !== account.member.id) return
        tasksReadAt = Date.now()
        const shown = taskNotice.value
        if (shown && !tasks.some(task => task.id === shown.id && task.status === shown.status)) clearTaskNotice()
        // 聊天正在占用气泡时，下一次轮询再处理任务，避免提前消费提示。
        if (unread.length) { tasksReadAt = 0; clearTaskNotice(); return }
        const next = nextPetTaskNotice(tasks, session.userId)
        if (next) {
          taskNotice.value = { ...next, status: tasks.find(task => task.id === next.id)!.status }
          resize(noticeVisible.value)
          clearTimeout(taskNoticeTimer)
          taskNoticeTimer = setTimeout(clearTaskNotice, 8000)
        }
      }
    } catch (error) {
      if (disposed) return
      tasksReadAt = 0
      clearTaskNotice()
      const status = error as { status?: number; response?: { status?: number } }
      if (status.status === 401 || status.response?.status === 401) {
        session.account = null
        ui.chatUnreadCount = 0
        ui.chatLatestUnread = null
        resize(false)
      }
    }
  },
  () => 10_000,
)
onMounted(() => {
  document.documentElement.classList.add('desktop-pet-surface')
  resize(false)
  poller.start()
})
onBeforeUnmount(() => {
  disposed = true
  poller.stop()
  clearTimeout(taskNoticeTimer)
  document.documentElement.classList.remove('desktop-pet-surface')
})
</script>
<template>
  <main class="desktop-pet">
    <button v-if="!session.authed" class="pet-login" @click="nativeAction('workspace')">
      打开 WAO 登录
    </button>
    <PetMessenger v-else @open="nativeAction('open-chat')" @visible="resize" />
    <button
      v-if="taskNotice && !noticeVisible"
      class="pet-login"
      @click="openTaskNotice"
    >
      {{ taskNotice.label }}
    </button>
    <div class="pet-body">
    <button v-if="native" class="pet-close" aria-label="隐藏桌面宠物" title="隐藏宠物，可从客户端或托盘恢复" @click="nativeAction('hide')">×</button>
    <button
      class="pet-avatar"
      aria-label="宠物：点击打开或收起 WAO，右键打开聊天，拖动调整位置"
      title="点击打开或收起 WAO，右键打开聊天，拖动调整位置"
      @pointerdown="pointerDown"
      @pointermove="pointerMove"
      @pointercancel="pointerStart = null"
      @click="toggleWorkspace"
      @contextmenu.prevent="nativeAction('open-chat')"
    >
      <PetSprite :state="noticeVisible ? 'waving' : 'idle'" />
    </button>
    </div>
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
.pet-body { position: relative; flex-shrink: 0; }
.pet-close { position: absolute; z-index: 1; right: 0; top: 0; width: 24px; height: 24px; border: 1px solid var(--wao-line); border-radius: 50%; background: var(--wao-bg-card); color: var(--wao-text); opacity: 0; cursor: pointer; }
.pet-body:hover .pet-close, .pet-body:focus-within .pet-close { opacity: 1; }
.pet-avatar {
  width: 90px;
  height: 100px;
  background: transparent;
  border: 0;
  padding: 0;
  cursor: grab;
  touch-action: none;
}
.pet-login {
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  border: 1px solid var(--wao-line);
  padding: 10px;
  border-radius: 12px;
}
</style>
