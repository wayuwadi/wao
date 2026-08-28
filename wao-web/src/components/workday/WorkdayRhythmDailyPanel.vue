<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import {
  fetchRhythmDashboard,
  setRhythmOccurrenceCompleted,
  setRhythmWorkdayStatus,
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
    emit('loaded', result)
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '每日面板暂时无法读取'
  } finally {
    loading.value = false
  }
}

async function toggle(item: RhythmOccurrence) {
  if (toggling.value) return
  toggling.value = item.id
  try {
    const saved = await setRhythmOccurrenceCompleted(item.id, !item.completedAt)
    const index = dashboard.value?.checklist.findIndex((entry) => entry.id === item.id) ?? -1
    if (dashboard.value && index >= 0) {
      dashboard.value.checklist[index] = saved
      dashboard.value.completedCount = dashboard.value.checklist.filter(
        (entry) => entry.completedAt,
      ).length
    }
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
onMounted(load)
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
          <button
            v-for="item in dashboard.checklist"
            :key="item.id"
            class="check-row"
            :class="[item.status.toLowerCase(), { done: item.completedAt }]"
            :disabled="toggling === item.id"
            @click="toggle(item)"
          >
            <i
              :class="
                item.completedAt ? 'ri-checkbox-circle-fill' : 'ri-checkbox-blank-circle-line'
              "
            ></i
            ><span
              ><b>{{ item.title }}</b
              ><small
                >· {{ statusText(item)
                }}<em v-if="item.occurrenceDate !== date">
                  · {{ item.occurrenceDate }} 的跨日事项</em
                ></small
              ></span
            ><strong>{{
              item.kind === 'PLAN' ? '自动联动' : item.completedAt ? '撤销' : '完成'
            }}</strong>
          </button>
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
  cursor: pointer;
}
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
    grid-template-columns: 33px minmax(0, 1fr) auto;
  }
}
</style>
