<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  fetchWorkdayRhythm,
  fetchWorkdayRhythmDay,
  saveWorkdayRhythm,
  saveWorkdayRhythmDay,
  sendEnergyNotification,
  type WorkdayRhythmEnergyAction,
} from '@/api/workdayRhythm'
import { fetchFeishuChats, type FeishuChat } from '@/api/system'
import { toast } from '@/composables/toast'
import {
  DEFAULT_WORKDAY_RHYTHM,
  blockProgress,
  cloneRhythmConfig,
  currentBlockIndex,
  defaultRhythmDay,
  minutesUntilEnd,
  parseRhythmConfig,
  parseRhythmDay,
  rhythmConfigError,
  shanghaiClock,
  type WorkdayRhythmConfig,
  type WorkdayRhythmDay,
} from '@/utils/workdayRhythm'

const CONFIG_DRAFT_KEY = 'wao-workday-rhythm-config-draft-v1'
const clock = ref(shanghaiClock())
const config = ref<WorkdayRhythmConfig>(cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM))
const day = ref<WorkdayRhythmDay>(defaultRhythmDay(clock.value.date, clock.value.weekday))
const loading = ref(true)
const loadError = ref('')
const dayState = ref<'idle' | 'dirty' | 'saving' | 'saved' | 'error'>('idle')
const editorOpen = ref(false)
const editorBusy = ref(false)
const editorError = ref('')
const editorDraft = ref<WorkdayRhythmConfig>(cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM))
const notificationChats = ref<FeishuChat[]>([])
const notificationChatsLoading = ref(false)
const notificationChatsError = ref('')
const energyNotificationState = ref<'idle' | 'sending' | 'sent' | 'error'>('idle')
const pendingEnergyNotification = ref<{ action: WorkdayRhythmEnergyAction; date: string } | null>(
  null,
)
let clockTimer: ReturnType<typeof setInterval> | null = null

const dayDraftKey = computed(() => `wao-workday-rhythm-day-draft-${clock.value.date}`)
const blockIndex = computed(() => currentBlockIndex(config.value.blocks, clock.value))
const currentBlock = computed(() => config.value.blocks[blockIndex.value] ?? null)
const nextBlock = computed(() => {
  if (blockIndex.value < 0) return config.value.blocks[0] ?? null
  return config.value.blocks[(blockIndex.value + 1) % config.value.blocks.length] ?? null
})
const currentProgress = computed(() =>
  currentBlock.value ? blockProgress(currentBlock.value, clock.value) : 0,
)
const remainingCopy = computed(() => {
  if (!currentBlock.value) return '等待下一个时间块'
  const remaining = minutesUntilEnd(currentBlock.value, clock.value)
  if (remaining >= 60) return `还剩 ${Math.floor(remaining / 60)} 小时 ${remaining % 60} 分钟`
  return `还剩 ${remaining} 分钟`
})
const completedCount = computed(
  () => config.value.blocks.filter((block) => day.value.completed.includes(block.id)).length,
)
const completionRate = computed(() =>
  config.value.blocks.length
    ? Math.round((completedCount.value / config.value.blocks.length) * 100)
    : 0,
)
const weekdayLabel = computed(
  () => ['一', '二', '三', '四', '五', '六', '日'][clock.value.weekday - 1],
)
const energyActionBusy = computed(
  () =>
    dayState.value === 'saving' ||
    energyNotificationState.value === 'sending' ||
    energyNotificationState.value === 'error',
)
const configuredChatMissing = computed(
  () =>
    !!editorDraft.value.energyNotification.chatId &&
    !notificationChats.value.some(
      (chat) => chat.chatId === editorDraft.value.energyNotification.chatId,
    ),
)

function loadLocalDayDraft(validIds: string[]): WorkdayRhythmDay | null {
  const raw = localStorage.getItem(dayDraftKey.value)
  if (!raw) return null
  const parsed = parseRhythmDay(raw, clock.value.date, clock.value.weekday, validIds)
  return parsed.date === clock.value.date ? parsed : null
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const loadedConfig = await fetchWorkdayRhythm()
    const validIds = loadedConfig.blocks.map((block) => block.id)
    const loadedDay = await fetchWorkdayRhythmDay(clock.value.date, clock.value.weekday, validIds)
    config.value = loadedConfig
    day.value = loadLocalDayDraft(validIds) ?? loadedDay
    dayState.value = localStorage.getItem(dayDraftKey.value) ? 'error' : 'idle'
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '今日节奏暂时无法读取'
  } finally {
    loading.value = false
  }
}

function markDayDirty() {
  if (dayState.value !== 'saving') dayState.value = 'dirty'
  localStorage.setItem(dayDraftKey.value, JSON.stringify(day.value))
}

async function persistDay(successMessage = ''): Promise<boolean> {
  if (dayState.value === 'saving') return false
  dayState.value = 'saving'
  localStorage.setItem(dayDraftKey.value, JSON.stringify(day.value))
  try {
    await saveWorkdayRhythmDay(day.value)
    localStorage.removeItem(dayDraftKey.value)
    dayState.value = 'saved'
    if (successMessage) toast(successMessage, 'ri-check-line')
    return true
  } catch (error) {
    dayState.value = 'error'
    toast(
      error instanceof Error ? `${error.message}，草稿仍在本机` : '保存失败，草稿仍在本机',
      'ri-error-warning-line',
    )
    return false
  }
}

function toggleBlock(id: string) {
  if (dayState.value === 'saving') return
  day.value.completed = day.value.completed.includes(id)
    ? day.value.completed.filter((entry) => entry !== id)
    : [...day.value.completed, id]
  markDayDirty()
  void persistDay(day.value.completed.includes(id) ? '时间块已记为完成' : '已撤销这次完成记录')
}

async function dispatchEnergyNotification(action: WorkdayRhythmEnergyAction, date: string) {
  const notification = config.value.energyNotification
  const selected = action === 'WATER' ? notification.notifyWater : notification.notifyMeditation
  if (!notification.enabled || !selected) return
  pendingEnergyNotification.value = { action, date }
  energyNotificationState.value = 'sending'
  try {
    const result = await sendEnergyNotification(action, date)
    if (!result.sent) {
      energyNotificationState.value = 'idle'
      pendingEnergyNotification.value = null
      return
    }
    energyNotificationState.value = 'sent'
    pendingEnergyNotification.value = null
    toast(`已通知「${result.chatName}」`, 'ri-send-plane-fill')
  } catch (error) {
    energyNotificationState.value = 'error'
    toast(
      error instanceof Error
        ? `记录已保存，但群通知失败：${error.message}`
        : '记录已保存，但群通知失败',
      'ri-error-warning-line',
    )
  }
}

async function retryEnergyNotification() {
  if (!pendingEnergyNotification.value) return
  await dispatchEnergyNotification(
    pendingEnergyNotification.value.action,
    pendingEnergyNotification.value.date,
  )
}

async function addWater() {
  if (energyActionBusy.value || day.value.waterCount >= 99) return
  day.value.waterCount += 1
  markDayDirty()
  if (await persistDay('已记录这次喝水')) {
    await dispatchEnergyNotification('WATER', day.value.date)
  }
}

async function toggleMeditation() {
  if (energyActionBusy.value) return
  day.value.meditated = !day.value.meditated
  const justRecorded = day.value.meditated
  markDayDirty()
  if (await persistDay(justRecorded ? '今天的冥想已记录' : '已撤销冥想记录')) {
    if (justRecorded) await dispatchEnergyNotification('MEDITATION', day.value.date)
  }
}

function toggleWorkday() {
  if (dayState.value === 'saving') return
  day.value.workdayActive = !day.value.workdayActive
  markDayDirty()
  void persistDay(day.value.workdayActive ? '今天已按工作日节奏启用' : '今天已切换为休息日')
}

function focusInput() {
  day.value.focus = day.value.focus.map((item) => item.slice(0, 100))
  markDayDirty()
}

function saveFocus() {
  day.value.focus = day.value.focus.map((item) => item.trim())
  markDayDirty()
  void persistDay('今日重点已保存')
}

function openEditor() {
  const savedDraft = parseRhythmConfig(localStorage.getItem(CONFIG_DRAFT_KEY) ?? '')
  editorDraft.value = savedDraft ?? cloneRhythmConfig(config.value)
  editorError.value = ''
  editorOpen.value = true
  if (editorDraft.value.energyNotification.enabled) void loadNotificationChats()
}

async function loadNotificationChats() {
  if (notificationChatsLoading.value) return
  notificationChatsLoading.value = true
  notificationChatsError.value = ''
  try {
    notificationChats.value = await fetchFeishuChats()
    if (!notificationChats.value.length)
      notificationChatsError.value = '机器人当前不在任何可选群聊中'
  } catch (error) {
    notificationChatsError.value =
      error instanceof Error ? error.message : '可用飞书群读取失败，请稍后重试'
  } finally {
    notificationChatsLoading.value = false
  }
}

function selectNotificationChat() {
  const selected = notificationChats.value.find(
    (chat) => chat.chatId === editorDraft.value.energyNotification.chatId,
  )
  editorDraft.value.energyNotification.chatName = selected?.name ?? ''
}

function resetEditor() {
  if (
    !window.confirm(
      '恢复任务中给出的原始工作日模板？当前编辑草稿会被替换，保存前不会影响线上配置。',
    )
  )
    return
  editorDraft.value = {
    ...cloneRhythmConfig(DEFAULT_WORKDAY_RHYTHM),
    energyNotification: { ...editorDraft.value.energyNotification },
  }
  editorError.value = ''
}

async function submitEditor() {
  const error = rhythmConfigError(editorDraft.value)
  if (error) {
    editorError.value = error
    return
  }
  editorBusy.value = true
  editorError.value = ''
  try {
    config.value = await saveWorkdayRhythm(editorDraft.value)
    day.value.completed = day.value.completed.filter((id) =>
      config.value.blocks.some((block) => block.id === id),
    )
    localStorage.removeItem(CONFIG_DRAFT_KEY)
    const pendingAction = pendingEnergyNotification.value?.action
    if (
      !config.value.energyNotification.enabled ||
      (pendingAction === 'WATER' && !config.value.energyNotification.notifyWater) ||
      (pendingAction === 'MEDITATION' && !config.value.energyNotification.notifyMeditation)
    ) {
      pendingEnergyNotification.value = null
      energyNotificationState.value = 'idle'
    }
    editorOpen.value = false
    toast('工作日节奏已保存并跨设备生效', 'ri-check-line')
  } catch (error) {
    editorError.value =
      error instanceof Error ? `${error.message}，草稿仍在本机` : '模板保存失败，草稿仍在本机'
  } finally {
    editorBusy.value = false
  }
}

watch(
  editorDraft,
  (value) => {
    if (editorOpen.value) localStorage.setItem(CONFIG_DRAFT_KEY, JSON.stringify(value))
  },
  { deep: true },
)

watch(
  () => editorDraft.value.energyNotification.enabled,
  (enabled) => {
    if (enabled && editorOpen.value && !notificationChats.value.length) {
      void loadNotificationChats()
    }
  },
)

watch(
  () => clock.value.date,
  (date, previous) => {
    if (date !== previous) {
      pendingEnergyNotification.value = null
      energyNotificationState.value = 'idle'
      void load()
    }
  },
)

onMounted(() => {
  void load()
  clockTimer = setInterval(() => {
    clock.value = shanghaiClock()
  }, 30_000)
})
onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<template>
  <main class="rhythm-page">
    <header class="page-head">
      <div>
        <div class="eyebrow">WORKDAY RHYTHM · 上海时间</div>
        <h1>今日节奏</h1>
        <p>守住精力边界，把每一段时间用在它真正该做的事上。</p>
      </div>
      <button class="settings-button" type="button" @click="openEditor">
        <i class="ri-settings-4-line"></i>调整节奏
      </button>
    </header>

    <div v-if="loading" class="state-card">
      <i class="ri-loader-4-line spin"></i>正在读取你的工作日节奏…
    </div>
    <div v-else-if="loadError" class="state-card error" role="alert">
      <i class="ri-error-warning-line"></i><b>今日节奏暂时没读出来</b><span>{{ loadError }}</span>
      <button type="button" @click="load">重新加载</button>
    </div>

    <template v-else>
      <section class="hero" :class="{ resting: !day.workdayActive }">
        <div class="hero-copy">
          <div class="date-line">
            <span>{{ clock.date }}</span
            ><span>星期{{ weekdayLabel }}</span
            ><button type="button" @click="toggleWorkday">
              {{ day.workdayActive ? '今天休息' : '按工作日执行' }}
            </button>
          </div>
          <template v-if="day.workdayActive && currentBlock">
            <small>此刻该做</small>
            <h2>{{ currentBlock.title }}</h2>
            <p>{{ currentBlock.guidance }}</p>
            <div class="time-meta">
              <b>{{ currentBlock.start }}—{{ currentBlock.end }}</b
              ><span>{{ remainingCopy }}</span>
            </div>
            <div
              class="time-progress"
              role="progressbar"
              :aria-valuenow="currentProgress"
              aria-valuemin="0"
              aria-valuemax="100"
            >
              <span :style="{ width: `${currentProgress}%` }"></span>
            </div>
            <div v-if="nextBlock" class="next-up">
              <i class="ri-skip-right-line"></i>接下来 {{ nextBlock.start }} · {{ nextBlock.title }}
            </div>
          </template>
          <template v-else>
            <small>恢复模式</small>
            <h2>今天不追赶进度</h2>
            <p>休息日不会自动记为失败。需要加班或调休时，再手动启用今天的工作日节奏。</p>
          </template>
        </div>
        <div class="hero-score">
          <div class="score-ring" :style="{ '--score': `${completionRate * 3.6}deg` }">
            <span
              ><b>{{ completedCount }}</b
              ><small>/ {{ config.blocks.length }}</small></span
            >
          </div>
          <p>
            {{ completionRate === 100 ? '今日节奏已闭环' : '完成一个时间块，就留下一次真实记录' }}
          </p>
        </div>
      </section>

      <div v-if="dayState === 'error'" class="save-banner error" role="alert">
        <i class="ri-cloud-off-line"></i><span>上次保存失败，未提交内容仍在本机。</span
        ><button type="button" @click="persistDay('今日记录已重新保存')">重试保存</button>
      </div>
      <div v-else-if="dayState === 'saving'" class="save-banner">
        <i class="ri-loader-4-line spin"></i><span>保存中…</span>
      </div>
      <div v-else-if="dayState === 'saved'" class="save-banner saved">
        <i class="ri-cloud-line"></i><span>已保存到你的 WAO 账号</span>
      </div>

      <section class="dashboard-grid">
        <article class="focus-card">
          <header>
            <div>
              <small>09:00 前列好</small>
              <h2>今日三个重点</h2>
            </div>
            <RouterLink to="/todo">打开真实待办<i class="ri-arrow-right-line"></i></RouterLink>
          </header>
          <p class="card-help">
            这里只写今天必须守住的结果；具体任务和进度仍以待办中的真实数据为准。
          </p>
          <label v-for="(_, index) in day.focus" :key="index">
            <span>{{ index + 1 }}</span
            ><input
              v-model="day.focus[index]"
              :disabled="dayState === 'saving'"
              maxlength="100"
              :placeholder="index === 0 ? '今天最重要、最难的一件事' : '另一个可观察的结果'"
              @input="focusInput"
            />
          </label>
          <footer>
            <small v-if="dayState === 'dirty'">草稿已保存在本机</small><span></span
            ><button type="button" :disabled="dayState === 'saving'" @click="saveFocus">
              {{ dayState === 'error' ? '重试保存' : '保存今日重点' }}
            </button>
          </footer>
        </article>

        <aside class="energy-card">
          <header>
            <div>
              <small>ENERGY CHECK</small>
              <h2>精力补给</h2>
            </div>
            <button
              type="button"
              aria-label="设置精力补给通知"
              title="设置精力补给通知"
              @click="openEditor"
            >
              <i class="ri-settings-4-line"></i>
            </button>
          </header>
          <button
            class="energy-action water"
            type="button"
            :disabled="energyActionBusy"
            @click="addWater"
          >
            <i class="ri-cup-line"></i
            ><span
              ><b>喝水 +1</b><small>今天已真实记录 {{ day.waterCount }} 次</small></span
            ><strong>{{ day.waterCount }}</strong>
          </button>
          <button
            class="energy-action meditate"
            :class="{ done: day.meditated }"
            type="button"
            :disabled="energyActionBusy"
            @click="toggleMeditation"
          >
            <i :class="day.meditated ? 'ri-checkbox-circle-fill' : 'ri-mental-health-line'"></i
            ><span
              ><b>至少冥想一次</b
              ><small>{{
                day.meditated ? '今天已经留过一次空白' : '累之前，先停下来几分钟'
              }}</small></span
            >
          </button>
          <p>
            <i class="ri-information-line"></i>只记录你实际做过的次数，不替你设定饮水或健康处方。
          </p>
          <div v-if="energyNotificationState === 'sending'" class="energy-notice sending">
            <i class="ri-loader-4-line spin"></i><span>记录已保存，正在通知群聊…</span>
          </div>
          <div
            v-else-if="energyNotificationState === 'error'"
            class="energy-notice error"
            role="alert"
          >
            <i class="ri-cloud-off-line"></i><span>记录已保存，群通知未发出。</span>
            <button type="button" @click="retryEnergyNotification">重试通知</button>
          </div>
          <div v-else-if="energyNotificationState === 'sent'" class="energy-notice sent">
            <i class="ri-send-plane-fill"></i><span>群通知已发出</span>
          </div>
        </aside>
      </section>

      <section class="timeline-card">
        <header>
          <div>
            <small>DAILY FLOW</small>
            <h2>完整的一天</h2>
          </div>
          <span>{{ completedCount }} / {{ config.blocks.length }} 已完成</span>
        </header>
        <div class="timeline">
          <button
            v-for="(block, index) in config.blocks"
            :key="block.id"
            type="button"
            class="time-block"
            :class="{
              current: day.workdayActive && index === blockIndex,
              done: day.completed.includes(block.id),
            }"
            :disabled="dayState === 'saving'"
            @click="toggleBlock(block.id)"
          >
            <span class="time">{{ block.start }}<i></i>{{ block.end }}</span>
            <span class="marker"
              ><i
                :class="
                  day.completed.includes(block.id)
                    ? 'ri-check-line'
                    : index === blockIndex && day.workdayActive
                      ? 'ri-play-fill'
                      : ''
                "
              ></i
            ></span>
            <span class="block-copy"
              ><b>{{ block.title }}</b
              ><small>{{ block.guidance }}</small></span
            >
            <span class="block-action">{{
              day.completed.includes(block.id) ? '已完成' : '完成打卡'
            }}</span>
          </button>
        </div>
      </section>
    </template>

    <template v-if="editorOpen">
      <div class="mask" @click="!editorBusy && (editorOpen = false)"></div>
      <section class="editor" role="dialog" aria-modal="true" aria-labelledby="rhythm-editor-title">
        <header>
          <div>
            <small>个人工作日配置</small>
            <h2 id="rhythm-editor-title">调整节奏与精力补给</h2>
          </div>
          <button type="button" aria-label="关闭" @click="editorOpen = false">
            <i class="ri-close-line"></i>
          </button>
        </header>
        <div class="editor-body">
          <p class="editor-intro">
            时间线与通知目标都按你的账号保存；编辑草稿会先留在本机，保存后才跨设备生效。
          </p>
          <section class="energy-editor" aria-labelledby="energy-editor-title">
            <header>
              <div>
                <small>ENERGY NOTIFICATION</small>
                <h3 id="energy-editor-title">精力补给群通知</h3>
              </div>
              <label class="toggle-field">
                <input v-model="editorDraft.energyNotification.enabled" type="checkbox" />
                <span>{{ editorDraft.energyNotification.enabled ? '已启用' : '未启用' }}</span>
              </label>
            </header>
            <p>喝水或首次记录冥想成功后，使用 WAO 已配置的飞书应用通知你选择的群。</p>
            <div v-if="editorDraft.energyNotification.enabled" class="energy-editor-fields">
              <label class="chat-field">
                <span>通知到</span>
                <select
                  v-model="editorDraft.energyNotification.chatId"
                  :disabled="notificationChatsLoading"
                  @change="selectNotificationChat"
                >
                  <option value="">请选择机器人所在的群</option>
                  <option
                    v-if="configuredChatMissing"
                    :value="editorDraft.energyNotification.chatId"
                  >
                    已保存 · {{ editorDraft.energyNotification.chatName || '未知群聊' }}
                  </option>
                  <option v-for="chat in notificationChats" :key="chat.chatId" :value="chat.chatId">
                    {{ chat.name }}
                  </option>
                </select>
              </label>
              <button
                class="refresh-chats"
                type="button"
                :disabled="notificationChatsLoading"
                @click="loadNotificationChats"
              >
                <i
                  :class="notificationChatsLoading ? 'ri-loader-4-line spin' : 'ri-refresh-line'"
                ></i>
                {{ notificationChatsLoading ? '读取中' : '刷新群聊' }}
              </button>
              <label class="action-field">
                <input v-model="editorDraft.energyNotification.notifyWater" type="checkbox" />
                <span><b>喝水 +1</b><small>每次真实记录后通知</small></span>
              </label>
              <label class="action-field">
                <input v-model="editorDraft.energyNotification.notifyMeditation" type="checkbox" />
                <span><b>冥想一下</b><small>每天首次记录后通知</small></span>
              </label>
            </div>
            <p v-if="notificationChatsError" class="chat-load-error" role="alert">
              <i class="ri-error-warning-line"></i
              >{{ notificationChatsError }}；请确认自动化中的飞书应用凭据可用。
            </p>
          </section>
          <div class="timeline-editor-title">
            <small>DAILY FLOW</small>
            <h3>时间线与行动提示</h3>
            <p>时间块必须首尾相接并完整覆盖一天。</p>
          </div>
          <div v-for="(block, index) in editorDraft.blocks" :key="block.id" class="block-editor">
            <div class="block-number">{{ index + 1 }}</div>
            <div class="time-fields">
              <label><span>开始</span><input v-model="block.start" type="time" /></label
              ><label><span>结束</span><input v-model="block.end" type="time" /></label>
            </div>
            <label class="title-field"
              ><span>名称</span><input v-model="block.title" maxlength="30"
            /></label>
            <label class="guidance-field"
              ><span>行动提示</span
              ><textarea v-model="block.guidance" maxlength="120" rows="2"></textarea>
            </label>
          </div>
          <p v-if="editorError" class="editor-error" role="alert">
            <i class="ri-error-warning-line"></i>{{ editorError }}
          </p>
        </div>
        <footer>
          <button class="reset" type="button" :disabled="editorBusy" @click="resetEditor">
            恢复原始模板</button
          ><span></span
          ><button type="button" :disabled="editorBusy" @click="editorOpen = false">稍后再说</button
          ><button class="primary" type="button" :disabled="editorBusy" @click="submitEditor">
            {{ editorBusy ? '保存中…' : '保存节奏' }}
          </button>
        </footer>
      </section>
    </template>
  </main>
</template>

<style scoped>
.rhythm-page {
  max-width: 1180px;
  margin: 0 auto;
  padding: 24px 28px 56px;
  color: var(--wao-text);
}
.page-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
}
.eyebrow,
article header small,
aside header small,
.timeline-card header small,
.editor header small {
  color: #267c65;
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 1.7px;
}
h1 {
  margin: 5px 0 1px;
  font-size: 25px;
}
.page-head p {
  margin: 0;
  color: var(--wao-text-weak);
  font-size: 13px;
}
button,
input,
textarea {
  font: inherit;
}
.settings-button {
  height: 36px;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 0 14px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.state-card {
  margin-top: 20px;
  min-height: 300px;
  border: 1px solid var(--wao-line);
  border-radius: 18px;
  background: var(--wao-bg-card);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  color: var(--wao-text-weak);
}
.state-card.error {
  flex-direction: column;
}
.state-card.error b {
  color: var(--wao-text-2);
}
.state-card.error button {
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  padding: 7px 14px;
  cursor: pointer;
}
.hero {
  position: relative;
  min-height: 245px;
  margin-top: 20px;
  padding: 26px 30px;
  border-radius: 20px;
  overflow: hidden;
  background: linear-gradient(128deg, #174f43, #267c65 62%, #3f9677);
  color: #fff;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 210px;
  align-items: center;
  gap: 30px;
  box-shadow: 0 18px 40px rgba(32, 99, 78, 0.16);
}
.hero::after {
  content: '';
  position: absolute;
  width: 340px;
  height: 340px;
  right: -125px;
  top: -145px;
  border: 1px solid rgba(255, 255, 255, 0.13);
  border-radius: 50%;
  box-shadow:
    0 0 0 48px rgba(255, 255, 255, 0.035),
    0 0 0 96px rgba(255, 255, 255, 0.025);
}
.hero.resting {
  background: linear-gradient(128deg, #344956, #526d75);
}
.hero-copy,
.hero-score {
  position: relative;
  z-index: 1;
}
.date-line {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.68);
  font-size: 11px;
}
.date-line span + span::before {
  content: '·';
  margin-right: 8px;
}
.date-line button {
  margin-left: 5px;
  border: 1px solid rgba(255, 255, 255, 0.22);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.85);
  padding: 3px 9px;
  cursor: pointer;
  font-size: 10px;
}
.hero-copy > small {
  display: block;
  margin-top: 20px;
  color: #b9e4d4;
  font-size: 11px;
  letter-spacing: 1px;
}
.hero h2 {
  margin: 3px 0 7px;
  font-size: clamp(28px, 4vw, 42px);
}
.hero-copy > p {
  max-width: 650px;
  margin: 0;
  color: rgba(255, 255, 255, 0.82);
  font-size: 13px;
  line-height: 1.7;
}
.time-meta {
  max-width: 560px;
  margin-top: 19px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
}
.time-meta b {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.time-meta span {
  color: #cce9df;
}
.time-progress {
  width: min(560px, 100%);
  height: 5px;
  margin-top: 7px;
  border-radius: 99px;
  background: rgba(255, 255, 255, 0.15);
  overflow: hidden;
}
.time-progress span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: #9ce3c8;
  transition: width 0.3s;
}
.next-up {
  margin-top: 11px;
  color: rgba(255, 255, 255, 0.64);
  font-size: 10.5px;
}
.next-up i {
  margin-right: 5px;
}
.hero-score {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.score-ring {
  --score: 0deg;
  width: 130px;
  height: 130px;
  padding: 10px;
  border-radius: 50%;
  background: conic-gradient(#b0ead5 var(--score), rgba(255, 255, 255, 0.13) 0);
}
.score-ring > span {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  background: #286f5c;
  display: flex;
  align-items: baseline;
  justify-content: center;
  color: #fff;
}
.score-ring b {
  align-self: center;
  font-size: 36px;
}
.score-ring small {
  align-self: center;
  margin: 12px 0 0 4px;
  color: rgba(255, 255, 255, 0.6);
}
.hero-score p {
  max-width: 170px;
  margin: 12px 0 0;
  color: rgba(255, 255, 255, 0.62);
  text-align: center;
  font-size: 10.5px;
  line-height: 1.5;
}
.save-banner {
  min-height: 32px;
  margin: 9px 2px -4px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--wao-text-faint);
  font-size: 10.5px;
}
.save-banner.saved {
  color: #267c65;
}
.save-banner.error {
  color: #a84545;
}
.save-banner button {
  border: 0;
  background: none;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}
.dashboard-grid {
  margin-top: 14px;
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(260px, 0.8fr);
  gap: 14px;
}
.focus-card,
.energy-card,
.timeline-card {
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
}
.focus-card {
  padding: 18px;
}
.focus-card header {
  display: flex;
  align-items: center;
}
.focus-card h2,
.energy-card h2,
.timeline-card h2 {
  margin: 2px 0 0;
  font-size: 16px;
}
.focus-card header a {
  margin-left: auto;
  color: #267c65;
  text-decoration: none;
  font-size: 11px;
}
.focus-card header a i {
  margin-left: 4px;
}
.card-help {
  margin: 8px 0 13px;
  color: var(--wao-text-faint);
  font-size: 10.5px;
}
.focus-card label {
  min-height: 42px;
  margin-top: 7px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  padding: 0 10px;
  display: flex;
  align-items: center;
  gap: 9px;
}
.focus-card label:focus-within {
  border-color: #71ad98;
  box-shadow: 0 0 0 3px rgba(62, 139, 112, 0.08);
}
.focus-card label span {
  width: 23px;
  height: 23px;
  border-radius: 7px;
  background: #e0f0e9;
  color: #267c65;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 700;
}
.focus-card input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--wao-text);
  font-size: 12.5px;
}
.focus-card footer {
  min-height: 35px;
  margin-top: 8px;
  display: flex;
  align-items: center;
}
.focus-card footer small {
  color: #9a741e;
}
.focus-card footer span {
  flex: 1;
}
.focus-card footer button {
  height: 31px;
  border: 0;
  border-radius: 8px;
  background: #267c65;
  color: #fff;
  padding: 0 12px;
  cursor: pointer;
  font-size: 11px;
}
.focus-card footer button:disabled {
  opacity: 0.55;
}
.energy-card {
  padding: 18px;
}
.energy-card > header {
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.energy-card > header button {
  width: 32px;
  height: 32px;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  background: var(--wao-bg-page);
  color: var(--wao-text-weak);
  display: grid;
  place-items: center;
  cursor: pointer;
}
.energy-action {
  width: 100%;
  min-height: 63px;
  margin-top: 8px;
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  background: var(--wao-bg-page);
  color: var(--wao-text);
  padding: 9px 11px;
  display: flex;
  align-items: center;
  gap: 10px;
  text-align: left;
  cursor: pointer;
}
.energy-action > i {
  width: 33px;
  height: 33px;
  border-radius: 10px;
  background: #dcecf2;
  color: #37738a;
  display: grid;
  place-items: center;
  font-size: 17px;
}
.energy-action > span {
  flex: 1;
  display: grid;
}
.energy-action b {
  font-size: 12.5px;
}
.energy-action small {
  margin-top: 2px;
  color: var(--wao-text-faint);
  font-size: 10px;
}
.energy-action strong {
  color: #37738a;
  font-size: 21px;
}
.energy-action.meditate > i {
  background: #efe9f6;
  color: #775b92;
}
.energy-action.meditate.done {
  border-color: #9ac7b7;
  background: #eff8f4;
}
.energy-action.meditate.done > i {
  background: #d9eee5;
  color: #267c65;
}
.energy-card > p {
  margin: 12px 2px 0;
  color: var(--wao-text-faint);
  font-size: 9.5px;
  line-height: 1.5;
}
.energy-card > p i {
  margin-right: 4px;
}
.energy-notice {
  min-height: 34px;
  margin-top: 10px;
  border-radius: 8px;
  padding: 7px 9px;
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--wao-text-weak);
  background: var(--wao-bg-page);
  font-size: 10px;
}
.energy-notice.error {
  color: #9b4141;
  background: #fff2f1;
}
.energy-notice.sent {
  color: #267c65;
  background: #eff8f4;
}
.energy-notice button {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-weight: 700;
}
.timeline-card {
  margin-top: 14px;
  padding: 18px;
}
.timeline-card > header {
  display: flex;
  align-items: center;
}
.timeline-card > header > span {
  margin-left: auto;
  color: var(--wao-text-faint);
  font-size: 11px;
}
.timeline {
  margin-top: 12px;
}
.time-block {
  position: relative;
  width: 100%;
  min-height: 73px;
  border: 0;
  border-top: 1px solid var(--wao-divider);
  background: transparent;
  color: var(--wao-text);
  padding: 8px 6px;
  display: grid;
  grid-template-columns: 98px 28px minmax(0, 1fr) 70px;
  align-items: center;
  gap: 10px;
  text-align: left;
  cursor: pointer;
}
.time-block:first-child {
  border-top: 0;
}
.time-block:hover {
  background: var(--wao-bg-hover);
}
.time {
  color: var(--wao-text-weak);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
  display: flex;
  align-items: center;
  gap: 4px;
}
.time i {
  width: 8px;
  height: 1px;
  background: var(--wao-text-faint);
}
.marker {
  position: relative;
  width: 22px;
  height: 22px;
  border: 1.5px solid #bdcbc6;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 11px;
}
.marker::after {
  content: '';
  position: absolute;
  top: 21px;
  left: 9px;
  width: 1px;
  height: 51px;
  background: var(--wao-line);
}
.time-block:last-child .marker::after {
  display: none;
}
.block-copy {
  min-width: 0;
  display: grid;
}
.block-copy b {
  font-size: 13px;
}
.block-copy small {
  margin-top: 3px;
  overflow: hidden;
  color: var(--wao-text-faint);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.block-action {
  color: var(--wao-text-faint);
  font-size: 10.5px;
  text-align: right;
}
.time-block.current {
  border-radius: 10px;
  background: #f0f8f5;
}
.time-block.current .marker {
  border-color: #267c65;
  background: #267c65;
}
.time-block.current .block-copy b,
.time-block.current .block-action {
  color: #267c65;
}
.time-block.done .marker {
  border-color: #6cad95;
  background: #6cad95;
}
.time-block.done .block-copy b {
  color: #4f7165;
  text-decoration: line-through;
}
.time-block.done .block-action {
  color: #4b856f;
}
.mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(18, 29, 26, 0.44);
}
.editor {
  position: fixed;
  z-index: 101;
  top: 4vh;
  left: 50%;
  transform: translateX(-50%);
  width: min(760px, calc(100vw - 28px));
  max-height: 92vh;
  border-radius: 17px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.editor > header {
  min-height: 68px;
  padding: 13px 19px;
  border-bottom: 1px solid var(--wao-divider);
  display: flex;
  align-items: center;
}
.editor > header h2 {
  margin: 2px 0 0;
  font-size: 17px;
}
.editor > header button {
  margin-left: auto;
  width: 31px;
  height: 31px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
}
.editor-body {
  padding: 15px 19px 22px;
  overflow-y: auto;
}
.editor-intro {
  margin: 0 0 11px;
  color: var(--wao-text-faint);
  font-size: 10.5px;
}
.energy-editor {
  margin-bottom: 16px;
  border: 1px solid #b9d8cc;
  border-radius: 13px;
  padding: 13px;
  background: #f1f8f5;
}
.energy-editor > header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.energy-editor h3,
.timeline-editor-title h3 {
  margin: 2px 0 0;
  color: var(--wao-text);
  font-size: 14px;
}
.energy-editor > p {
  margin: 8px 0 0;
  color: var(--wao-text-weak);
  font-size: 10.5px;
  line-height: 1.5;
}
.toggle-field {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #267c65;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
}
.toggle-field input,
.action-field input {
  accent-color: #267c65;
}
.energy-editor-fields {
  margin-top: 11px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(125px, 0.65fr) minmax(125px, 0.65fr);
  align-items: end;
  gap: 8px;
}
.chat-field {
  display: grid;
  gap: 4px;
}
.chat-field > span {
  color: var(--wao-text-faint);
  font-size: 9.5px;
}
.chat-field select {
  width: 100%;
  height: 36px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  padding: 0 8px;
  font-size: 11px;
}
.refresh-chats {
  height: 36px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
  padding: 0 9px;
  cursor: pointer;
  font-size: 10px;
}
.action-field {
  min-height: 36px;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  background: var(--wao-bg-card);
  padding: 5px 7px;
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.action-field > span {
  display: grid;
}
.action-field b {
  font-size: 10.5px;
}
.action-field small {
  color: var(--wao-text-faint);
  font-size: 8.5px;
}
.energy-editor > .chat-load-error {
  color: #9b4141;
}
.chat-load-error i {
  margin-right: 4px;
}
.timeline-editor-title {
  margin: 2px 2px 9px;
}
.timeline-editor-title p {
  margin: 4px 0 0;
  color: var(--wao-text-faint);
  font-size: 10px;
}
.block-editor {
  margin-top: 8px;
  padding: 10px;
  border: 1px solid var(--wao-line);
  border-radius: 11px;
  background: var(--wao-bg-page);
  display: grid;
  grid-template-columns: 28px 190px minmax(130px, 0.7fr) minmax(180px, 1.3fr);
  align-items: end;
  gap: 8px;
}
.block-number {
  width: 25px;
  height: 25px;
  margin-bottom: 6px;
  border-radius: 8px;
  background: #dceee6;
  color: #267c65;
  display: grid;
  place-items: center;
  font-size: 10px;
  font-weight: 700;
}
.time-fields {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}
.block-editor label {
  display: grid;
  gap: 4px;
}
.block-editor label span {
  color: var(--wao-text-faint);
  font-size: 9.5px;
}
.block-editor input,
.block-editor textarea {
  width: 100%;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  padding: 7px 8px;
  outline: none;
  font-size: 11.5px;
}
.block-editor textarea {
  min-height: 52px;
  resize: vertical;
}
.block-editor input:focus,
.block-editor textarea:focus {
  border-color: #71ad98;
  box-shadow: 0 0 0 3px rgba(62, 139, 112, 0.08);
}
.editor-error {
  margin: 12px 2px 0;
  color: #aa4242;
  font-size: 11px;
}
.editor-error i {
  margin-right: 5px;
}
.editor > footer {
  min-height: 62px;
  padding: 10px 19px;
  border-top: 1px solid var(--wao-divider);
  display: flex;
  align-items: center;
  gap: 8px;
}
.editor > footer span {
  flex: 1;
}
.editor > footer button {
  height: 34px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  padding: 0 13px;
  cursor: pointer;
}
.editor > footer .reset {
  border: 0;
  color: var(--wao-text-faint);
}
.editor > footer .primary {
  border: 0;
  background: #267c65;
  color: #fff;
}
.editor > footer button:disabled {
  opacity: 0.55;
  cursor: wait;
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
  .rhythm-page {
    padding: 16px 12px 96px;
  }
  .page-head {
    align-items: center;
  }
  .page-head p {
    display: none;
  }
  .settings-button {
    padding: 0 10px;
  }
  .hero {
    min-height: 0;
    padding: 22px 18px;
    grid-template-columns: 1fr;
    gap: 20px;
  }
  .hero-score {
    display: none;
  }
  .hero h2 {
    font-size: 30px;
  }
  .dashboard-grid {
    grid-template-columns: 1fr;
  }
  .focus-card,
  .energy-card,
  .timeline-card {
    padding: 14px;
  }
  .time-block {
    min-height: 86px;
    grid-template-columns: 72px 24px minmax(0, 1fr);
    gap: 7px;
  }
  .block-copy small {
    white-space: normal;
    line-height: 1.35;
  }
  .block-action {
    display: none;
  }
  .marker::after {
    height: 64px;
  }
  .editor {
    top: 10px;
    width: calc(100vw - 20px);
    max-height: calc(100dvh - 20px);
  }
  .editor-body {
    padding: 12px;
  }
  .energy-editor-fields {
    grid-template-columns: minmax(0, 1fr) auto;
  }
  .action-field {
    min-height: 42px;
  }
  .block-editor {
    grid-template-columns: 25px 1fr 1fr;
  }
  .time-fields {
    grid-column: 2 / -1;
  }
  .title-field {
    grid-column: 2 / -1;
  }
  .guidance-field {
    grid-column: 2 / -1;
  }
  .editor > footer .reset {
    display: none;
  }
}
</style>
