<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { AssistantStatus } from '@/api/assistant'
import PetSprite from './PetSprite.vue'
import PetMessenger from '@/components/chat/PetMessenger.vue'
import { useFloatingDrag } from '@/composables/floatingDrag'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { useSessionStore } from '@/stores/session'
import { useWebPetStore } from '@/stores/webPet'
defineProps<{ assistantStatus: AssistantStatus }>()
const data = useDataStore()
const ui = useUiStore()
const session = useSessionStore()
const webPet = useWebPetStore()
const {
  elementRef: petRef,
  style: petStyle,
  dragging,
  moving,
  horizontalDirection,
  onPointerDown,
  consumeClick,
} = useFloatingDrag('wao-assistant-fab-position-v1')
const notice = ref<{ id: string; label: string } | null>(null)
let timer: ReturnType<typeof setTimeout> | undefined
const snapshot = computed(() =>
  data.tasks
    .filter((t) => ['待验证', 'AI 进行中', '待办'].includes(t.status))
    .map((t) => `${t.id}:${t.status}`)
    .sort()
    .join('|'),
)
watch(
  [snapshot, () => data.taskLoadState, () => session.userId],
  () => {
    if (data.taskLoadState !== 'loaded' || !session.userId || !webPet.settings.showTaskNotices)
      return
    const key = `wao-pet-task-seen:${session.userId}`
    try {
      if (localStorage.getItem(key) === snapshot.value) return
      localStorage.setItem(key, snapshot.value)
    } catch {
      /* Reminders remain bounded by the timer. */
    }
    const task =
      data.tasks.find((t) => t.status === '待验证') ??
      data.tasks.find((t) => t.status === 'AI 进行中') ??
      data.tasks.find((t) => t.status === '待办')
    clearTimeout(timer)
    notice.value = task
      ? {
          id: task.id,
          label:
            task.status === '待验证'
              ? '有任务等你验收'
              : task.status === 'AI 进行中'
                ? 'AI 正在处理任务'
                : '有待办任务，点击查看',
        }
      : null
    timer = setTimeout(() => {
      notice.value = null
    }, 8000)
  },
  { immediate: true },
)
function click(event: MouseEvent) {
  if (!consumeClick(event)) ui.openChat()
}
function openNotice() {
  if (notice.value) ui.openTask(notice.value.id)
  notice.value = null
}
onMounted(() => {
  void webPet.load()
})
onBeforeUnmount(() => clearTimeout(timer))
</script>
<template>
  <section
    ref="petRef"
    class="wao-web-pet"
    :class="{ dragging }"
    :style="petStyle"
    aria-label="WAO 网页宠物"
    @pointerdown="onPointerDown"
  >
    <PetMessenger />
    <button
      v-if="notice && !ui.chatUnreadCount && !ui.chatOpen"
      class="pet-notice"
      @pointerdown.stop
      @click="openNotice"
    >
      {{ notice.label }}
    </button>
    <button
      class="pet-avatar"
      title="点击打开聊天，拖动调整位置"
      aria-label="宠物：点击打开聊天，拖动调整位置"
      @click="click"
    >
      <PetSprite
        :state="moving ? `running-${horizontalDirection}` : 'idle'"
        :paused="!moving && (dragging || !webPet.settings.idleAnimation)"
      />
    </button>
  </section>
</template>
<style scoped>
.wao-web-pet {
  position: fixed;
  right: 24px;
  bottom: 32px;
  z-index: 480;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
  width: max-content;
  max-width: calc(100vw - 24px);
  touch-action: none;
}
.pet-avatar {
  width: 90px;
  height: 100px;
  padding: 0;
  background: transparent;
  border: 0;
  cursor: grab;
}
.pet-notice {
  padding: 10px 14px;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  color: var(--wao-text-2);
  background: var(--wao-bg-card);
  cursor: pointer;
  font-size: 12px;
}
</style>
