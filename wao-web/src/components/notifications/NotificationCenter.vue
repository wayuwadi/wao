<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  fetchNotificationCenter,
  markNotificationCenterRead,
  type NotificationCenterSnapshot,
  type NotificationEvent,
} from '@/api/notificationCenter'
import { createVisibilityPoller } from '@/composables/visibilityPolling'

const router = useRouter()
const open = ref(false)
const activeSection = ref<'events' | 'usage'>('events')
const snapshot = ref<NotificationCenterSnapshot | null>(null)
const loading = ref(false)
const refreshing = ref(false)
const loadError = ref('')
const readError = ref('')
let loadPromise: Promise<boolean> | null = null

const unreadLabel = computed(() => {
  const count = snapshot.value?.unreadCount ?? 0
  return count > 99 ? '99+' : String(count)
})
const hasUnread = computed(() => (snapshot.value?.unreadCount ?? 0) > 0)

const eventMeta: Record<NotificationEvent['type'], { icon: string; label: string }> = {
  INTERNAL: { icon: 'ri-notification-3-line', label: 'WAO 通知' },
  TASK_CREATED: { icon: 'ri-add-circle-line', label: '新建任务' },
  TASK_COMPLETED: { icon: 'ri-checkbox-circle-line', label: '任务完成' },
  AI_RUN: { icon: 'ri-robot-2-line', label: 'AI 运行' },
  LOGIN: { icon: 'ri-login-circle-line', label: '成员登录' },
}

async function load(silent = false): Promise<boolean> {
  if (loadPromise) return loadPromise
  if (silent && snapshot.value) refreshing.value = true
  else loading.value = !snapshot.value
  loadError.value = ''
  loadPromise = fetchNotificationCenter()
    .then((value) => {
      snapshot.value = value
      return true
    })
    .catch((cause: unknown) => {
      loadError.value = cause instanceof Error ? cause.message : '通知中心读取失败，请重试'
      return false
    })
    .finally(() => {
      loading.value = false
      refreshing.value = false
      loadPromise = null
    })
  return loadPromise
}

async function markRead() {
  if (!hasUnread.value) return
  readError.value = ''
  try {
    const readAt = await markNotificationCenterRead()
    if (snapshot.value) snapshot.value = { ...snapshot.value, unreadCount: 0, readAt }
  } catch (cause) {
    readError.value =
      cause instanceof Error ? cause.message : '已读状态同步失败，下次打开会继续保留提醒'
  }
}

async function toggle() {
  open.value = !open.value
  if (!open.value) return
  const loaded = await load(Boolean(snapshot.value))
  if (loaded) await markRead()
}

function close() {
  open.value = false
}

async function refresh() {
  const loaded = await load(true)
  if (loaded && open.value) await markRead()
}

const notificationPolling = createVisibilityPoller(refresh, () => 60_000)

async function navigate(path: string | null) {
  if (!path) return
  close()
  await router.push(path)
}

function timeText(value: string | null): string {
  if (!value) return '尚未记录'
  const time = new Date(value)
  if (Number.isNaN(time.getTime())) return '时间不可用'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(time)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && open.value) close()
}

onMounted(() => {
  void load()
  window.addEventListener('keydown', onKeydown)
  notificationPolling.start(false)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  notificationPolling.stop()
})
</script>

<template>
  <button
    class="notification-trigger"
    data-desktop-action="notifications"
    type="button"
    :title="hasUnread ? `通知中心，${snapshot?.unreadCount} 条未读` : '通知中心'"
    aria-haspopup="dialog"
    :aria-expanded="open"
    @click="toggle"
  >
    <i class="ri-notification-3-line"></i>
    <span v-if="hasUnread" class="notification-badge" aria-live="polite">{{ unreadLabel }}</span>
  </button>

  <Teleport to="body">
    <div v-if="open" class="notification-layer">
      <button
        class="notification-mask"
        type="button"
        aria-label="关闭通知中心"
        @click="close"
      ></button>
      <aside
        class="notification-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby="notification-title"
      >
        <header class="notification-head">
          <div>
            <h2 id="notification-title">通知中心</h2>
            <p>真实系统动态与使用入口</p>
          </div>
          <button type="button" aria-label="打开通知设置" title="通知设置" @click="navigate('/settings?tab=notifications')">
            <i class="ri-settings-3-line"></i>
          </button>
          <button type="button" aria-label="关闭通知中心" @click="close">
            <i class="ri-close-line"></i>
          </button>
        </header>

        <section class="report-links" aria-label="查看统计">
          <button type="button" @click="navigate('/dashboard')">
            <i class="ri-bar-chart-box-line"></i
            ><span><b>AI 使用统计</b><small>Token 与次数</small></span>
          </button>
          <button type="button" @click="navigate('/system-reports')">
            <i class="ri-file-chart-line"></i
            ><span><b>系统报告</b><small>日周月归档</small></span>
          </button>
          <button type="button" @click="navigate('/request-monitoring')">
            <i class="ri-pulse-line"></i
            ><span><b>请求监控</b><small>慢请求与失败</small></span>
          </button>
        </section>

        <nav class="notification-tabs" aria-label="通知中心内容">
          <button
            type="button"
            :class="{ active: activeSection === 'events' }"
            @click="activeSection = 'events'"
          >
            最新动态
          </button>
          <button
            type="button"
            :class="{ active: activeSection === 'usage' }"
            @click="activeSection = 'usage'"
          >
            成员使用
          </button>
          <button class="refresh" type="button" :disabled="loading || refreshing" @click="refresh">
            <i :class="refreshing ? 'ri-loader-4-line spin' : 'ri-refresh-line'"></i
            >{{ refreshing ? '刷新中' : '刷新' }}
          </button>
        </nav>

        <div v-if="loading" class="notification-state" role="status">
          <i class="ri-loader-4-line spin"></i>正在读取真实记录…
        </div>
        <div v-else-if="loadError && !snapshot" class="notification-state error" role="alert">
          <i class="ri-wifi-off-line"></i><b>通知中心暂时不可用</b><span>{{ loadError }}</span>
          <button type="button" @click="load()">重新加载</button>
        </div>
        <template v-else-if="snapshot">
          <div v-if="loadError || readError" class="inline-error" role="alert">
            {{ loadError || readError }}
            <button v-if="loadError" type="button" @click="refresh">重试</button>
          </div>

          <div v-if="activeSection === 'events'" class="notification-content">
            <button
              v-for="event in snapshot.events"
              :key="event.id"
              class="event-row"
              :class="[`severity-${event.severity.toLowerCase()}`, { clickable: event.targetPath }]"
              type="button"
              :disabled="!event.targetPath"
              @click="navigate(event.targetPath)"
            >
              <span class="event-icon"><i :class="eventMeta[event.type].icon"></i></span>
              <span class="event-copy" :title="`${eventMeta[event.type].label} · ${event.title} · ${event.actorName ? `${event.actorName} · ` : ''}${event.detail}`">
                <b>{{ event.title }}</b>
                <small> · {{ event.actorName ? `${event.actorName} · ` : '' }}{{ event.detail }}</small>
              </span>
              <time>{{ timeText(event.occurredAt) }}</time>
              <i v-if="event.targetPath" class="ri-arrow-right-s-line event-arrow"></i>
            </button>
            <div v-if="!snapshot.events.length" class="notification-state empty">
              <i class="ri-notification-off-line"></i><b>还没有可展示的动态</b>
              <span>任务、AI 运行或成员登录产生真实记录后会出现在这里。</span>
            </div>
          </div>

          <div v-else class="notification-content usage-list">
            <article v-for="member in snapshot.loginUsage" :key="member.memberId" class="usage-row">
              <span class="member-avatar" :style="{ background: member.color }">{{
                member.memberName.slice(0, 1)
              }}</span>
              <span class="member-copy"
                ><b>{{ member.memberName }}</b
                ><small>最近登录：{{ timeText(member.lastLoginAt) }}</small></span
              >
              <span class="usage-count"
                ><b>{{ member.count7Days }}</b
                ><small>近 7 天</small></span
              >
              <span class="usage-count"
                ><b>{{ member.count30Days }}</b
                ><small>近 30 天</small></span
              >
            </article>
            <div v-if="!snapshot.loginUsage.length" class="notification-state empty">
              <i class="ri-team-line"></i><b>还没有成员资料</b
              ><span>成员目录出现真实账号后再统计。</span>
            </div>
            <p class="usage-note">
              <i class="ri-information-line"></i
              >登录次数从通知中心发布后开始累计，历史次数不会推测补齐。
            </p>
          </div>
        </template>
      </aside>
    </div>
  </Teleport>
</template>

<style scoped>
.notification-trigger {
  position: relative;
  width: 32px;
  height: 32px;
  border: 0;
  background: none;
  border-radius: 8px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
}
.notification-trigger:hover,
.notification-trigger[aria-expanded='true'] {
  background: var(--wao-bg-hover-2);
  color: var(--wao-primary-text);
}
.notification-trigger i {
  font-size: 17px;
}
.notification-badge {
  position: absolute;
  top: -4px;
  right: -5px;
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border: 2px solid var(--wao-bg-card);
  border-radius: 9px;
  background: var(--wao-danger);
  color: #fff;
  font-size: 9px;
  line-height: 13px;
  font-weight: 700;
}
.notification-layer {
  position: fixed;
  inset: 0;
  z-index: 1200;
}
.notification-mask {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  border: 0;
  background: rgba(16, 18, 28, 0.22);
  cursor: default;
}
.notification-panel {
  position: absolute;
  top: 8px;
  right: 8px;
  bottom: 8px;
  width: min(430px, calc(100vw - 16px));
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
  color: var(--wao-text);
}
.notification-head {
  min-height: 70px;
  padding: 16px 18px;
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--wao-line);
}
.notification-head > div {
  flex: 1;
  min-width: 0;
}
.notification-head h2 {
  margin: 0;
  font-size: 18px;
}
.notification-head p {
  margin: 4px 0 0;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.notification-head button {
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
.notification-head button:hover {
  background: var(--wao-bg-hover-2);
}
.report-links {
  padding: 12px 14px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  border-bottom: 1px solid var(--wao-line);
}
.report-links button {
  min-width: 0;
  padding: 10px 8px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-2);
  text-align: left;
  cursor: pointer;
  display: flex;
  align-items: flex-start;
  gap: 7px;
}
.report-links button:hover {
  border-color: var(--wao-primary-line);
  background: var(--wao-primary-tint);
}
.report-links button > i {
  margin-top: 1px;
  color: var(--wao-primary-text);
  font-size: 16px;
}
.report-links span {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.report-links b {
  font-size: 12px;
  white-space: nowrap;
}
.report-links small {
  color: var(--wao-text-weak);
  font-size: 10px;
  white-space: nowrap;
}
.notification-tabs {
  min-height: 45px;
  padding: 0 14px;
  display: flex;
  align-items: center;
  gap: 18px;
  border-bottom: 1px solid var(--wao-line);
}
.notification-tabs button {
  height: 45px;
  padding: 0;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--wao-text-weak);
  font-size: 12px;
  cursor: pointer;
}
.notification-tabs button.active {
  border-color: var(--wao-primary);
  color: var(--wao-primary-text);
  font-weight: 600;
}
.notification-tabs .refresh {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.notification-tabs .refresh:disabled {
  cursor: default;
  opacity: 0.6;
}
.notification-content {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 5px 0 16px;
}
.event-row {
  width: 100%;
  min-height: 46px;
  padding: 7px 16px;
  border: 0;
  border-bottom: 1px solid var(--wao-divider);
  background: transparent;
  color: inherit;
  text-align: left;
  display: flex;
  align-items: center;
  gap: 9px;
}
.event-row.clickable {
  cursor: pointer;
}
.event-row:disabled {
  opacity: 1;
}
.event-row.clickable:hover {
  background: var(--wao-bg-hover);
}
.event-icon {
  flex: 0 0 auto;
  width: 27px;
  height: 27px;
  border-radius: 8px;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  display: flex;
  align-items: center;
  justify-content: center;
}
.severity-success .event-icon {
  background: var(--wao-success-bg);
  color: var(--wao-success);
}
.severity-warning .event-icon {
  background: var(--wao-warning-bg);
  color: var(--wao-warning-strong);
}
.event-copy {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  display: block;
  line-height: 18px;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.event-copy b {
  font-size: 13px;
}
.event-copy small {
  color: var(--wao-text-3);
  font-size: 11px;
}
.event-row time {
  flex: 0 0 auto;
  color: var(--wao-text-faint);
  font-size: 10px;
}
.event-arrow {
  align-self: center;
  color: var(--wao-text-faint);
}
.notification-state {
  flex: 1;
  min-height: 180px;
  padding: 28px;
  color: var(--wao-text-weak);
  font-size: 13px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  text-align: center;
}
.notification-state > i {
  font-size: 24px;
}
.notification-state.error {
  color: var(--wao-danger-strong);
}
.notification-state button,
.inline-error button {
  padding: 6px 10px;
  border: 1px solid currentColor;
  border-radius: 7px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.notification-state.empty {
  min-height: 240px;
}
.inline-error {
  margin: 10px 14px 4px;
  padding: 8px 10px;
  border: 1px solid var(--wao-danger-line);
  border-radius: 8px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  font-size: 11px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.inline-error button {
  margin-left: auto;
  padding: 3px 7px;
}
.usage-list {
  padding: 0 14px 16px;
}
.usage-row {
  min-height: 66px;
  border-bottom: 1px solid var(--wao-divider);
  display: flex;
  align-items: center;
  gap: 10px;
}
.member-avatar {
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}
.member-copy {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.member-copy b {
  overflow: hidden;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.member-copy small {
  color: var(--wao-text-weak);
  font-size: 10px;
}
.usage-count {
  flex: 0 0 48px;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
}
.usage-count b {
  font-size: 15px;
}
.usage-count small {
  color: var(--wao-text-weak);
  font-size: 9px;
}
.usage-note {
  margin: 12px 0 0;
  padding: 9px 10px;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  font-size: 10px;
  line-height: 16px;
  display: flex;
  gap: 5px;
}
.spin {
  animation: notification-spin 0.9s linear infinite;
}
@keyframes notification-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 560px) {
  .notification-panel {
    inset: 0;
    width: 100%;
    border: 0;
    border-radius: 0;
  }
  .report-links {
    grid-template-columns: 1fr;
  }
  .report-links button {
    align-items: center;
  }
  .report-links span {
    flex-direction: row;
    align-items: center;
    justify-content: space-between;
    flex: 1;
  }
}
</style>
