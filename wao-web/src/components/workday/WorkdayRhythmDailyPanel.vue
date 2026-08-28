<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { fetchAssistantConversation } from '@/api/assistant'
import {
  fetchRhythmDashboard,
  setRhythmOccurrenceCompleted,
  setRhythmWorkdayStatus,
  type RhythmDashboard,
  type RhythmOccurrence,
} from '@/api/workdayRhythm'
import {
  createTodayTianrenReading,
  fetchTodayTianrenReading,
  type TianrenReading,
} from '@/api/tianren'
import { toast } from '@/composables/toast'

const props = defineProps<{ date: string; reloadKey?: number }>()
const emit = defineEmits<{ loaded: [dashboard: RhythmDashboard] }>()
const dashboard = ref<RhythmDashboard | null>(null)
const loading = ref(true)
const error = ref('')
const toggling = ref('')
const reading = ref<TianrenReading | null>(null)
const casting = ref(false)
const readingSummary = ref('')
const summaryLoading = ref(false)
const summaryError = ref('')

const completionRate = computed(() =>
  dashboard.value?.totalCount
    ? Math.round((dashboard.value.completedCount / dashboard.value.totalCount) * 100)
    : 0,
)
const workItems = computed(
  () => dashboard.value?.checklist.filter((item) => item.category === 'WORK') ?? [],
)
const lifeItems = computed(
  () => dashboard.value?.checklist.filter((item) => item.category === 'LIFE') ?? [],
)

function statusText(item: RhythmOccurrence) {
  if (item.status === 'ON_TIME') return '准时完成'
  if (item.status === 'COMPLETED_LATE') return '逾期完成'
  if (item.status === 'LATE') return '已逾期'
  if (item.status === 'UPCOMING') return `${item.startTime} 后开始`
  return `${item.dueNextDay ? '次日 ' : ''}${item.dueTime} 前完成`
}

function compactSummary(value: string) {
  const plain = value
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/[#>*_`~[\]]/g, '')
    .replace(/\([^)]*\)/g, '')
    .replace(/\s+/g, ' ')
    .trim()
  return plain.length > 220 ? `${plain.slice(0, 220).trim()}…` : plain
}

async function loadReadingSummary(current: TianrenReading | null) {
  readingSummary.value = ''
  summaryError.value = ''
  if (!current?.conversationId) return
  const readingId = current.id
  summaryLoading.value = true
  try {
    const detail = await fetchAssistantConversation(current.conversationId)
    if (reading.value?.id !== readingId) return
    const latest = [...detail.messages]
      .reverse()
      .find((message) => message.role === 'assistant')?.content
    readingSummary.value = latest ? compactSummary(latest) : ''
  } catch (cause) {
    if (reading.value?.id !== readingId) return
    summaryError.value = cause instanceof Error ? cause.message : '今日卦象摘要暂时无法读取'
  } finally {
    if (reading.value?.id === readingId) summaryLoading.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [result, todayReading] = await Promise.all([
      fetchRhythmDashboard(props.date),
      fetchTodayTianrenReading(),
    ])
    dashboard.value = result
    reading.value = todayReading
    emit('loaded', result)
    void loadReadingSummary(todayReading)
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

async function cast() {
  if (casting.value) return
  casting.value = true
  try {
    reading.value = await createTodayTianrenReading()
    await load()
    toast('今日卦象已保存', 'ri-sparkling-line')
  } catch (cause) {
    toast(cause instanceof Error ? cause.message : '今日卦象生成失败', 'ri-error-warning-line')
  } finally {
    casting.value = false
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
          <div class="completion">
            <b>{{ completionRate }}%</b
            ><span>{{ dashboard.completedCount }} / {{ dashboard.totalCount }}</span>
          </div>
        </header>
        <div v-if="!dashboard.checklist.length" class="empty-list">
          <i class="ri-checkbox-circle-line"></i>今天没有适用的必做项
        </div>
        <div v-else class="checklist-columns">
          <section v-if="workItems.length">
            <h3><i class="ri-briefcase-4-line"></i>工作</h3>
            <button
              v-for="item in workItems"
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
                  >{{ statusText(item)
                  }}<em v-if="item.occurrenceDate !== date">
                    · {{ item.occurrenceDate }} 的跨日事项</em
                  ></small
                ></span
              ><strong>{{
                item.kind === 'PLAN' ? '自动联动' : item.completedAt ? '撤销' : '完成'
              }}</strong>
            </button>
          </section>
          <section v-if="lifeItems.length">
            <h3><i class="ri-heart-3-line"></i>生活</h3>
            <button
              v-for="item in lifeItems"
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
                ><small>{{ statusText(item) }}</small></span
              ><strong>{{ item.completedAt ? '撤销' : '完成' }}</strong>
            </button>
          </section>
        </div>
      </article>

      <article v-if="dashboard.settings.tianrenEnabled" class="daily-card hexagram-card">
        <header>
          <div>
            <small>DAILY HEXAGRAM</small>
            <h2>每日卦象</h2>
          </div>
          <span v-if="dashboard.settings.tianrenRequired">已纳入必做</span>
        </header>
        <template v-if="reading">
          <div class="hex-result">
            <strong>{{ reading.primary.symbol }}</strong
            ><span
              ><b>{{ reading.primary.name }}卦</b
              ><small v-if="reading.relating"
                >之 {{ reading.relating.name }}卦 · {{ reading.changingLines.length }} 个变爻</small
              ><small v-else>今日无变爻</small></span
            >
          </div>
          <div class="hex-guidance">
            <b><i class="ri-sparkling-2-line"></i>今日启示</b>
            <p v-if="summaryLoading">正在读取已保存的 AI 自照摘要…</p>
            <p v-else-if="readingSummary">{{ readingSummary }}</p>
            <p v-else-if="summaryError" class="error">
              {{ summaryError }} <button @click="loadReadingSummary(reading)">重试</button>
            </p>
            <p v-else>
              这份卦象还没有 AI 自照；进入详情后可基于真实卦象生成思想方向与当日行动提醒。
            </p>
          </div>
          <RouterLink to="/tianren"
            >查看完整卦象与 AI 自照<i class="ri-arrow-right-line"></i
          ></RouterLink>
        </template>
        <template v-else>
          <p class="hex-empty">今天还没有起卦；由你主动开始，不会因为打开页面自动生成。</p>
          <button class="cast-button" :disabled="casting" @click="cast">
            <i :class="casting ? 'ri-loader-4-line spin' : 'ri-sparkling-line'"></i
            >{{ casting ? '正在起卦…' : '起出今日一卦' }}
          </button>
        </template>
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
.restore-auto,
.hex-guidance button {
  border: 0;
  background: none;
  color: var(--wao-primary);
  cursor: pointer;
}
.daily-card {
  min-width: 0;
  padding: 18px;
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
}
.checklist-card {
  grid-area: mustdo;
}
.hexagram-card {
  grid-area: hexagram;
}
.panel-head,
.hexagram-card > header {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: flex-start;
}
.panel-head small,
.hexagram-card header small {
  font-size: 9px;
  letter-spacing: 0.18em;
  color: var(--wao-primary);
}
.panel-head h2,
.hexagram-card h2 {
  margin: 3px 0;
  font-size: 16px;
}
.panel-head p {
  margin: 0;
  color: var(--wao-text-weak);
  font-size: 10px;
}
.completion {
  text-align: right;
}
.completion b {
  display: block;
  font-size: 22px;
  color: var(--wao-primary);
}
.completion span {
  font-size: 9px;
  color: var(--wao-text-weak);
}
.checklist-columns {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-top: 14px;
}
.checklist-columns h3 {
  margin: 0 0 6px;
  font-size: 11px;
  color: var(--wao-text-2);
}
.checklist-columns h3 i {
  margin-right: 5px;
}
.check-row {
  width: 100%;
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr) auto;
  gap: 7px;
  align-items: center;
  margin-top: 6px;
  padding: 8px 9px;
  border: 1px solid var(--wao-line);
  border-radius: 9px;
  background: var(--wao-bg-page);
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.check-row > i {
  font-size: 17px;
  color: var(--wao-text-faint);
}
.check-row span {
  display: grid;
  gap: 1px;
  min-width: 0;
}
.check-row b {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
}
.check-row small {
  color: var(--wao-text-weak);
  font-size: 8.5px;
}
.check-row em {
  font-style: normal;
}
.check-row strong {
  font-size: 8.5px;
  color: var(--wao-primary);
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
.hexagram-card > header > span {
  font-size: 9px;
  color: #97651f;
}
.hex-result {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 12px 0;
}
.hex-result > strong {
  font-size: 46px;
  color: #8e6534;
}
.hex-result span {
  display: grid;
  gap: 3px;
}
.hex-result b {
  font-family: 'STKaiti', 'KaiTi', serif;
  font-size: 18px;
}
.hex-result small {
  font-size: 9.5px;
  color: var(--wao-text-weak);
}
.hex-guidance {
  margin: 10px 0;
  padding: 10px;
  border-radius: 10px;
  background: var(--wao-bg-sub);
}
.hex-guidance > b {
  font-size: 10px;
  color: var(--wao-primary);
}
.hex-guidance > b i {
  margin-right: 5px;
}
.hex-guidance p,
.hex-empty {
  margin: 6px 0 0;
  color: var(--wao-text-weak);
  font-size: 10px;
  line-height: 1.55;
}
.hex-guidance p {
  display: -webkit-box;
  overflow: hidden;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
}
.hex-guidance .error {
  color: var(--wao-danger-strong);
}
.hexagram-card > a,
.cast-button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 0;
  background: none;
  color: var(--wao-primary);
  font-size: 10.5px;
  cursor: pointer;
  text-decoration: none;
}
.hex-empty {
  margin: 18px 0 12px;
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
  .checklist-columns {
    grid-template-columns: 1fr;
  }
  .check-row {
    grid-template-columns: 20px minmax(0, 1fr);
  }
  .check-row > strong {
    display: none;
  }
}
</style>
