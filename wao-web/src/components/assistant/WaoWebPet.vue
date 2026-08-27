<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { AssistantStatus } from '@/api/assistant'
import raccoonUrl from '@/assets/wao-fruit-kernel-raccoon.png'
import { useFloatingDrag } from '@/composables/floatingDrag'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import type { Task, TaskStatus } from '@/types'

interface PetNotice {
  id: string
  icon: string
  label: string
  detail: string
  taskId?: string
  tone: 'review' | 'running' | 'todo' | 'neutral' | 'error'
}

const props = defineProps<{ assistantStatus: AssistantStatus }>()
const emit = defineEmits<{ (event: 'open-assistant'): void }>()
const data = useDataStore()
const ui = useUiStore()
const noticeIndex = ref(0)
let noticeTimer: ReturnType<typeof setInterval> | null = null
const {
  elementRef: petRef,
  style: petStyle,
  dragging: petDragging,
  onPointerDown: startPetDrag,
  consumeClick,
} = useFloatingDrag('wao-assistant-fab-position-v1')

const priorityOrder = { P0: 0, P1: 1, P2: 2, P3: 3 }

function tasksWithStatus(status: TaskStatus): Task[] {
  return data.tasks
    .filter((task) => task.status === status)
    .sort((left, right) => {
      const byPriority = priorityOrder[left.pr] - priorityOrder[right.pr]
      if (byPriority !== 0) return byPriority
      return (left.due || '9999-12-31').localeCompare(right.due || '9999-12-31')
    })
}

function taskNotice(
  id: string,
  tasks: Task[],
  icon: string,
  label: string,
  suffix: string,
  tone: PetNotice['tone'],
): PetNotice | null {
  const first = tasks[0]
  if (!first) return null
  return {
    id,
    icon,
    label: `${label} · ${tasks.length} ${suffix}`,
    detail: first.title,
    taskId: first.id,
    tone,
  }
}

const notices = computed<PetNotice[]>(() => {
  if (data.taskLoadState === 'loading') {
    return [{
      id: 'loading', icon: 'ri-loader-4-line', label: '正在读取任务动态',
      detail: '问答入口仍可使用', tone: 'neutral',
    }]
  }
  if (data.taskLoadState === 'error') {
    return [{
      id: 'error', icon: 'ri-error-warning-line', label: '任务动态暂时不可用',
      detail: data.taskLoadError || '请稍后重试', tone: 'error',
    }]
  }

  const result = [
    taskNotice('review', tasksWithStatus('待验证'), 'ri-checkbox-circle-line', 'AI 已完成', '项等你验收', 'review'),
    taskNotice('running', tasksWithStatus('AI 进行中'), 'ri-sparkling-2-line', 'AI 正在工作', '项执行中', 'running'),
    taskNotice('todo', tasksWithStatus('待办'), 'ri-calendar-check-line', '待办提醒', '项待办', 'todo'),
  ].filter((notice): notice is PetNotice => notice !== null)

  if (result.length) return result
  return [{
    id: 'assistant',
    icon: props.assistantStatus.configured ? 'ri-chat-smile-2-line' : 'ri-cloud-off-line',
    label: props.assistantStatus.configured ? '今天的任务动态已清空' : 'WAO AI 暂时离线',
    detail: props.assistantStatus.configured ? '有问题随时问我' : props.assistantStatus.message,
    tone: props.assistantStatus.configured ? 'neutral' : 'error',
  }]
})

const currentNotice = computed(() => notices.value[noticeIndex.value % notices.value.length]!)
const actionableCount = computed(() => data.tasks.filter((task) =>
  ['AI 进行中', '待验证', '待办'].includes(task.status),
).length)

function restartNoticeTimer() {
  if (noticeTimer) clearInterval(noticeTimer)
  noticeTimer = null
  if (notices.value.length > 1) {
    noticeTimer = setInterval(() => {
      noticeIndex.value = (noticeIndex.value + 1) % notices.value.length
    }, 7_000)
  }
}

function openAssistant(event: MouseEvent) {
  if (!consumeClick(event)) emit('open-assistant')
}

function openNotice(event: MouseEvent) {
  if (consumeClick(event)) return
  if (currentNotice.value.taskId) ui.openTask(currentNotice.value.taskId)
  else emit('open-assistant')
}

watch(
  () => notices.value.map((notice) => notice.id).join('|'),
  () => {
    noticeIndex.value = 0
    restartNoticeTimer()
  },
)

onMounted(restartNoticeTimer)
onBeforeUnmount(() => {
  if (noticeTimer) clearInterval(noticeTimer)
})
</script>

<template>
  <section
    ref="petRef"
    class="wao-web-pet"
    :class="{ dragging: petDragging }"
    :style="petStyle"
    aria-label="WAO AI 网页宠物"
    @pointerdown="startPetDrag"
  >
    <Transition name="pet-notice" mode="out-in">
      <button
        :key="currentNotice.id"
        class="pet-notice"
        :class="`tone-${currentNotice.tone}`"
        type="button"
        aria-live="polite"
        :title="currentNotice.taskId ? `打开任务：${currentNotice.detail}` : '打开 WAO AI'"
        @click="openNotice"
      >
        <i :class="[currentNotice.icon, { spin: currentNotice.id === 'loading' }]"></i>
        <span>
          <b>{{ currentNotice.label }}</b>
          <small>{{ currentNotice.detail }}</small>
        </span>
        <i :class="currentNotice.taskId ? 'ri-arrow-right-s-line' : 'ri-chat-3-line'"></i>
      </button>
    </Transition>

    <button
      class="pet-avatar"
      type="button"
      title="拖动调整位置，点击打开 WAO AI"
      aria-label="打开 WAO AI，拖动可调整宠物位置"
      @click="openAssistant"
    >
      <span v-if="actionableCount" class="pet-badge" :aria-label="`${actionableCount} 项任务动态`">
        {{ actionableCount > 99 ? '99+' : actionableCount }}
      </span>
      <img :src="raccoonUrl" alt="" draggable="false" />
      <span class="pet-status" :class="{ online: assistantStatus.configured }" aria-hidden="true"></span>
    </button>
    <span class="pet-name">WAO AI</span>
  </section>
</template>

<style scoped>
.wao-web-pet {
  position: fixed;
  right: 18px;
  bottom: 12px;
  z-index: 70;
  width: min(280px, calc(100vw - 24px));
  min-height: 116px;
  touch-action: none;
  user-select: none;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: flex-end;
}
.wao-web-pet.dragging { cursor: grabbing; }
.pet-notice {
  width: 230px;
  min-height: 58px;
  margin: 0 54px -11px 0;
  padding: 9px 10px;
  border: 1px solid color-mix(in srgb, var(--wao-primary) 20%, var(--wao-line));
  border-radius: 14px 14px 4px 14px;
  background: color-mix(in srgb, var(--wao-bg-card) 96%, transparent);
  color: var(--wao-text-2);
  box-shadow: 0 12px 32px rgba(29, 37, 70, .16);
  backdrop-filter: blur(10px);
  cursor: pointer;
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) 16px;
  align-items: center;
  gap: 7px;
}
.pet-notice:hover { border-color: var(--wao-primary-line); transform: translateY(-1px); }
.pet-notice > i:first-child { color: var(--wao-primary); font-size: 19px; }
.pet-notice > i:last-child { color: var(--wao-text-weak); font-size: 16px; }
.pet-notice span, .pet-notice b, .pet-notice small {
  min-width: 0;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
}
.pet-notice b { color: var(--wao-text); font-size: 12px; font-weight: 700; }
.pet-notice small { margin-top: 3px; color: var(--wao-text-weak); font-size: 11px; }
.pet-notice.tone-review { border-color: color-mix(in srgb, #d89614 34%, var(--wao-line)); }
.pet-notice.tone-review > i:first-child { color: #d89614; }
.pet-notice.tone-running > i:first-child { animation: pet-spark 1.7s ease-in-out infinite; }
.pet-notice.tone-error { border-color: color-mix(in srgb, #e5484d 36%, var(--wao-line)); }
.pet-notice.tone-error > i:first-child { color: #d94b50; }
.pet-avatar {
  position: relative;
  width: 102px;
  height: 100px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: grab;
  filter: drop-shadow(0 10px 11px rgba(55, 39, 79, .24));
  animation: pet-breathe 3.2s ease-in-out infinite;
}
.dragging .pet-avatar { cursor: grabbing; animation: none; }
.pet-avatar:hover { filter: drop-shadow(0 13px 14px rgba(68, 51, 100, .3)); }
.pet-avatar img { width: 100%; height: 100%; object-fit: contain; object-position: center bottom; pointer-events: none; }
.pet-status {
  position: absolute;
  right: 8px;
  bottom: 13px;
  width: 10px;
  height: 10px;
  border: 2px solid var(--wao-bg-card);
  border-radius: 50%;
  background: #f0a33a;
  box-shadow: 0 0 0 3px rgba(240, 163, 58, .18);
}
.pet-status.online { background: #4fc58b; box-shadow: 0 0 0 3px rgba(79, 197, 139, .2); }
.pet-badge {
  position: absolute;
  z-index: 1;
  right: 4px;
  top: 5px;
  min-width: 22px;
  height: 22px;
  padding: 0 5px;
  border: 2px solid var(--wao-bg-card);
  border-radius: 999px;
  background: #e5484d;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
}
.pet-name {
  position: absolute;
  right: 20px;
  bottom: 2px;
  padding: 2px 8px;
  border-radius: 999px;
  background: linear-gradient(135deg, #4358d6, #684fd3);
  color: #fff;
  box-shadow: 0 5px 14px rgba(67, 88, 214, .28);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: .04em;
  pointer-events: none;
}
.spin { animation: pet-spin 1s linear infinite; }
.pet-notice-enter-active, .pet-notice-leave-active { transition: opacity .18s ease, transform .18s ease; }
.pet-notice-enter-from, .pet-notice-leave-to { opacity: 0; transform: translateY(5px) scale(.98); }
@keyframes pet-breathe { 0%, 100% { transform: translateY(0) rotate(-1deg); } 50% { transform: translateY(-4px) rotate(1deg); } }
@keyframes pet-spark { 0%, 100% { transform: scale(.92) rotate(-6deg); opacity: .72; } 50% { transform: scale(1.12) rotate(5deg); opacity: 1; } }
@keyframes pet-spin { to { transform: rotate(360deg); } }

@media (max-width: 760px) {
  .wao-web-pet {
    right: 72px;
    bottom: calc(71px + env(safe-area-inset-bottom));
    width: min(238px, calc(100vw - 92px));
    min-height: 100px;
  }
  .pet-notice { width: 190px; min-height: 52px; margin-right: 46px; padding: 8px; }
  .pet-avatar { width: 82px; height: 82px; }
  .pet-name { right: 13px; }
}

@media (prefers-reduced-motion: reduce) {
  .pet-avatar, .pet-notice.tone-running > i:first-child, .spin { animation: none; }
  .pet-notice-enter-active, .pet-notice-leave-active { transition: none; }
}
</style>
