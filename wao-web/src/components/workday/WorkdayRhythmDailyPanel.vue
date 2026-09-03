<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  fetchRhythmDashboard,
  setRhythmOccurrenceCompleted,
  setRhythmWorkdayStatus,
  updateRhythmTimer,
  type RhythmDashboard,
  type RhythmOccurrence,
} from '@/api/workdayRhythm'
import { toast } from '@/composables/toast'

const props = defineProps<{ date: string; reloadKey?: number }>()
const emit = defineEmits<{ loaded: [dashboard: RhythmDashboard] }>()
const dashboard = ref<RhythmDashboard | null>(null)
const loading = ref(true)
const error = ref('')
const toggling = ref('')
const timerTick = ref(0)
const timerAnchors = new Map<string, { remaining: number; receivedAt: number }>()
const timerFinishFailed = new Set<string>()
let timerInterval: ReturnType<typeof setInterval> | null = null

function statusText(item: RhythmOccurrence) {
  if (item.status === 'ON_TIME') return '准时完成'
  if (item.status === 'COMPLETED_LATE') return '逾期完成'
  if (item.status === 'LATE') return '已逾期'
  if (item.status === 'UPCOMING') return `${item.startTime} 后开始`
  return `${item.dueNextDay ? '次日 ' : ''}${item.dueTime} 前完成`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const result = await fetchRhythmDashboard(props.date)
    dashboard.value = result
    result.checklist.forEach(anchorTimer)
    emit('loaded', result)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '每日面板暂时无法读取'
  } finally {
    loading.value = false
  }
}

function anchorTimer(item: RhythmOccurrence) {
  if (item.timerRemainingSeconds === null) return
  timerAnchors.set(item.id, { remaining: item.timerRemainingSeconds, receivedAt: Date.now() })
}

function remainingSeconds(item: RhythmOccurrence) {
  timerTick.value
  const anchor = timerAnchors.get(item.id)
  if (!anchor) return item.timerRemainingSeconds ?? 0
  if (!item.timerRunning) return anchor.remaining
  return Math.max(0, anchor.remaining - Math.floor((Date.now() - anchor.receivedAt) / 1000))
}

function timerText(item: RhythmOccurrence) {
  const total = remainingSeconds(item)
  return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`
}

function replaceOccurrence(saved: RhythmOccurrence) {
  const index = dashboard.value?.checklist.findIndex((entry) => entry.id === saved.id) ?? -1
  if (!dashboard.value || index < 0) return
  dashboard.value.checklist[index] = saved
  dashboard.value.completedCount = dashboard.value.checklist.filter(
    (entry) => entry.completedAt,
  ).length
  anchorTimer(saved)
}

async function timerAction(
  item: RhythmOccurrence,
  action: 'START' | 'PAUSE' | 'RESET',
  finished = false,
) {
  if (toggling.value) return
  if (!finished) timerFinishFailed.delete(item.id)
  toggling.value = item.id
  try {
    const saved = await updateRhythmTimer(item.id, action)
    timerFinishFailed.delete(item.id)
    replaceOccurrence(saved)
    if (saved.completedAt) {
      toast(`${saved.title}倒计时结束，今天的仪式完成了`, 'ri-timer-flash-line')
    } else if (!finished) {
      toast(
        action === 'START'
          ? `已开始${saved.title}`
          : action === 'PAUSE'
            ? `已暂停${saved.title}`
            : `已重置${saved.title}`,
        action === 'START' ? 'ri-play-line' : 'ri-check-line',
      )
    }
  } catch (cause) {
    if (finished) timerFinishFailed.add(item.id)
    toast(
      cause instanceof Error ? cause.message : '倒计时状态保存失败，请重试',
      'ri-error-warning-line',
    )
  } finally {
    toggling.value = ''
  }
}

async function toggle(item: RhythmOccurrence) {
  if (toggling.value) return
  toggling.value = item.id
  try {
    const saved = await setRhythmOccurrenceCompleted(item.id, !item.completedAt)
    replaceOccurrence(saved)
    toast(saved.completedAt ? `${saved.title}已完成` : `已撤销${saved.title}`, 'ri-check-line')
  } catch (cause) {
    toast(cause instanceof Error ? cause.message : '清单状态保存失败', 'ri-error-warning-line')
  } finally {
    toggling.value = ''
  }
}

async function restoreAutomaticWorkday() {
  if (!dashboard.value) return
  try {
    dashboard.value = await setRhythmWorkdayStatus(props.date, null)
    emit('loaded', dashboard.value)
  } catch (cause) {
    toast(
      cause instanceof Error ? cause.message : '自动工作日状态恢复失败',
      'ri-error-warning-line',
    )
  }
}

watch(
  () => [props.date, props.reloadKey],
  () => void load(),
)
onMounted(() => {
  void load()
  timerInterval = setInterval(() => {
    timerTick.value += 1
    dashboard.value?.checklist.forEach((item) => {
      if (
        item.itemType === 'COUNTDOWN' &&
        item.timerRunning &&
        !item.completedAt &&
        remainingSeconds(item) === 0 &&
        !timerFinishFailed.has(item.id)
      ) {
        void timerAction(item, 'PAUSE', true)
      }
    })
  }, 250)
})
onBeforeUnmount(() => {
  if (timerInterval) clearInterval(timerInterval)
})
</script>

<template>
  <div class="daily-panel">
    <div v-if="loading" class="panel-state">
      <i class="ri-loader-4-line spin"></i>正在整理今天的必做事项…
    </div>
    <div v-else-if="error" class="panel-state error" role="alert">
      <i class="ri-error-warning-line"></i><span>{{ error }}</span
      ><button @click="load">重试</button>
    </div>
    <template v-else-if="dashboard">
      <article class="daily-card checklist-card">
        <header class="panel-head">
          <div>
            <small>DAILY MUST-DO</small>
            <h2>每日必做</h2>
            <p>
              {{ dashboard.workWeekLabel }} · {{ dashboard.workday ? '按工作日执行' : '今天休息' }}
              <button
                v-if="dashboard.workdayOverride !== null"
                class="restore-auto"
                @click="restoreAutomaticWorkday"
              >
                恢复自动判断
              </button>
            </p>
          </div>
        </header>
        <div v-if="!dashboard.checklist.length" class="empty-list">
          <i class="ri-checkbox-circle-line"></i>今天没有适用的必做项
        </div>
        <div v-else class="checklist-list">
          <article
            v-for="item in dashboard.checklist"
            :key="item.id"
            class="check-row"
            :class="[item.status.toLowerCase(), { done: item.completedAt }]"
          >
            <button
              v-if="item.itemType !== 'COUNTDOWN'"
              class="check-toggle"
              type="button"
              :disabled="toggling === item.id"
              :aria-label="item.completedAt ? `撤销${item.title}` : `完成${item.title}`"
              @click="toggle(item)"
            >
              <i
                :class="
                  item.completedAt ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'
                "
              ></i>
            </button>
            <i v-else class="ri-timer-line timer-icon"></i>
            <span>
              <b>{{ item.title }}</b>
              <small>
                · {{ statusText(item)
                }}<em v-if="item.occurrenceDate !== date">
                  · {{ item.occurrenceDate }} 的跨日事项</em
                >
              </small>
            </span>
            <strong v-if="item.itemType !== 'COUNTDOWN'">{{
              item.kind === 'PLAN' ? '自动联动' : item.completedAt ? '撤销' : '完成'
            }}</strong>
            <div v-else class="timer-controls" :aria-label="`${item.title}倒计时`">
              <b class="timer-value" aria-live="polite">{{ timerText(item) }}</b>
              <button
                v-if="!item.completedAt"
                type="button"
                :disabled="toggling === item.id"
                @click="timerAction(item, item.timerRunning ? 'PAUSE' : 'START')"
              >
                <i :class="item.timerRunning ? 'ri-pause-line' : 'ri-play-line'"></i
                >{{
                  item.timerRunning
                    ? '暂停'
                    : remainingSeconds(item) < (item.durationSeconds ?? 0)
                      ? '继续'
                      : '开始'
                }}
              </button>
              <button
                v-if="remainingSeconds(item) < (item.durationSeconds ?? 0)"
                type="button"
                :disabled="toggling === item.id"
                @click="timerAction(item, 'RESET')"
              >
                <i class="ri-restart-line"></i>重置
              </button>
            </div>
          </article>
        </div>
      </article>
    </template>
  </div>
</template>

<style scoped>
.daily-panel {
  display: contents;
}
.panel-state {
  grid-column: 1/-1;
  min-height: 150px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
}
.panel-state.error {
  color: var(--wao-danger-strong);
}
.panel-state button,
.restore-auto {
  border: 0;
  background: none;
  color: #267c65;
  cursor: pointer;
}
.daily-card {
  height: 100%;
  min-width: 0;
  min-height: 0;
  box-sizing: border-box;
  padding: 18px;
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
  overflow-y: auto;
}
.checklist-card {
  grid-area: mustdo;
}
.panel-head {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: flex-start;
}
.panel-head small {
  font-size: 9px;
  letter-spacing: 0.18em;
  color: #267c65;
}
.panel-head h2 {
  margin: 3px 0;
  font-size: 16px;
}
.panel-head p {
  margin: 0;
  color: var(--wao-text-weak);
  font-size: 10px;
}
.checklist-list {
  margin-top: 14px;
}
.check-row {
  width: 100%;
  display: grid;
  grid-template-columns: 33px minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  min-height: 63px;
  margin-top: 8px;
  padding: 9px 11px;
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  background: var(--wao-bg-page);
  color: inherit;
  text-align: left;
}
.check-toggle,
.check-row > i {
  width: 33px;
  height: 33px;
  border-radius: 10px;
  background: #e0f0e9;
  display: grid;
  place-items: center;
  font-size: 17px;
  color: #267c65;
}
.check-toggle {
  border: 0;
  cursor: pointer;
}
.timer-icon {
  box-sizing: border-box;
}
.check-row span {
  min-width: 0;
  display: flex;
  align-items: baseline;
  gap: 4px;
  overflow: hidden;
}
.check-row b {
  flex: none;
  white-space: nowrap;
  font-size: 12.5px;
}
.check-row small {
  min-width: 0;
  overflow: hidden;
  color: var(--wao-text-weak);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.check-row em {
  font-style: normal;
}
.check-row strong {
  font-size: 9.5px;
  color: #267c65;
}
.timer-controls {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 5px;
}
.timer-controls .timer-value {
  min-width: 48px;
  color: var(--wao-primary);
  font-size: 14px;
  font-variant-numeric: tabular-nums;
  text-align: center;
}
.timer-controls button {
  height: 28px;
  padding: 0 7px;
  border: 1px solid var(--wao-line-control);
  border-radius: 7px;
  background: var(--wao-bg-card);
  color: var(--wao-primary);
  cursor: pointer;
  font-size: 9px;
}
.timer-controls button i {
  margin-right: 3px;
}
.check-row.late {
  border-color: color-mix(in srgb, var(--wao-danger) 42%, var(--wao-line));
  background: color-mix(in srgb, var(--wao-danger) 5%, var(--wao-bg-card));
}
.check-row.late small {
  color: var(--wao-danger-strong);
}
.check-row.done {
  opacity: 0.7;
}
.check-row.done > i {
  color: #2f9674;
}
.empty-list {
  margin-top: 14px;
  padding: 20px;
  border: 1px dashed var(--wao-line);
  border-radius: 11px;
  text-align: center;
  color: var(--wao-text-weak);
}
.spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 760px) {
  .daily-card {
    padding: 14px;
  }
  .check-row {
    grid-template-columns: 33px minmax(0, 1fr);
  }
  .check-row > strong,
  .timer-controls {
    grid-column: 2;
    justify-self: start;
  }
}
</style>
