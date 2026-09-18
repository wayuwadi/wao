<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  createActionPlan,
  fetchActionPlans,
  saveActionNote,
  setActionPlanCompleted,
  toggleActionCheckin,
  updateActionPlan,
  type ActionPlan,
  type SaveActionPlan,
} from '@/api/actionPlans'
import { toast } from '@/composables/toast'

const WEEKDAYS = [
  { value: 1, label: '一' }, { value: 2, label: '二' }, { value: 3, label: '三' },
  { value: 4, label: '四' }, { value: 5, label: '五' }, { value: 6, label: '六' },
  { value: 7, label: '日' },
]
const DRAFT_KEY = 'wao-action-plan-draft-v1'
const today = isoDate(new Date())
const selectedDate = ref(today)
const plans = ref<ActionPlan[]>([])
const loading = ref(true)
const loadError = ref('')
const activePlanId = ref('')
const busyItemId = ref('')
const statusBusy = ref(false)
const editorOpen = ref(false)
const editingId = ref('')
const editorBusy = ref(false)
const noteDraft = ref('')
const noteState = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
const pendingNotePlanId = ref('')
const pendingNoteDate = ref('')
const pendingNoteContent = ref('')
let noteTimer: ReturnType<typeof setTimeout> | null = null

interface ItemForm { id?: string; title: string; weekdays: number[] }
const form = reactive<SaveActionPlan>({
  title: '', vision: '', startDate: today, targetDate: plusDays(today, 99),
  reminderTime: '20:00', reminderEnabled: false,
  items: [{ title: '', weekdays: [1, 2, 3, 4, 5] }],
})

const activePlan = computed(() => plans.value.find((plan) => plan.id === activePlanId.value) ?? plans.value[0] ?? null)
const allDone = computed(() => Boolean(activePlan.value?.scheduledCount)
  && activePlan.value?.completedCount === activePlan.value?.scheduledCount)
const dateLabel = computed(() => {
  if (selectedDate.value === today) return '今天'
  const date = new Date(`${selectedDate.value}T00:00:00`)
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日`
})
const reminderCopy = computed(() => {
  const plan = activePlan.value
  if (!plan?.reminderEnabled) return '提醒未开启'
  if (plan.reminderState === 'SENT') return '今天已发到飞书私聊'
  if (plan.reminderState === 'FAILED') return '今天提醒失败，系统会重试'
  return `每天 ${plan.reminderTime?.slice(0, 5)} 检查当日行动并提醒`
})

function isoDate(date: Date) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(date)
}
function plusDays(value: string, days: number) {
  const date = new Date(`${value}T12:00:00+08:00`)
  date.setDate(date.getDate() + days)
  return isoDate(date)
}
function shiftDate(days: number) {
  const next = plusDays(selectedDate.value, days)
  if (next <= today) selectedDate.value = next
}
function selectToday() { selectedDate.value = today }

async function load(keepPlan = true) {
  loading.value = true
  loadError.value = ''
  try {
    const next = await fetchActionPlans(selectedDate.value)
    plans.value = next
    if (!keepPlan || !next.some((plan) => plan.id === activePlanId.value)) {
      activePlanId.value = next.find((plan) => plan.status === 'ACTIVE')?.id ?? next[0]?.id ?? ''
    }
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '行动计划读取失败'
  } finally {
    loading.value = false
  }
}

watch(selectedDate, () => {
  if (noteTimer) void flushNote()
  void load()
})
watch(activePlan, (plan) => {
  noteDraft.value = plan?.note ?? ''
  noteState.value = 'idle'
}, { immediate: true })
watch(noteDraft, (value, oldValue) => {
  if (!activePlan.value || value === oldValue || value === activePlan.value.note) return
  queueNoteSave()
})

async function toggle(itemId: string) {
  const plan = activePlan.value
  if (!plan || busyItemId.value) return
  busyItemId.value = itemId
  try {
    const updated = await toggleActionCheckin(plan.id, itemId, selectedDate.value)
    replacePlan(updated)
    if (updated.scheduledCount && updated.completedCount === updated.scheduledCount) {
      toast('今天安排的行动已经全部完成', 'ri-checkbox-circle-fill')
    }
  } catch (error) {
    toast(error instanceof Error ? error.message : '打卡保存失败，请重试', 'ri-error-warning-line')
  } finally {
    busyItemId.value = ''
  }
}

function queueNoteSave() {
  if (noteTimer) clearTimeout(noteTimer)
  const plan = activePlan.value
  if (!plan) return
  pendingNotePlanId.value = plan.id
  pendingNoteDate.value = selectedDate.value
  pendingNoteContent.value = noteDraft.value
  noteState.value = 'saving'
  noteTimer = setTimeout(() => void flushNote(), 650)
}
async function flushNote() {
  if (noteTimer) clearTimeout(noteTimer)
  noteTimer = null
  const planId = pendingNotePlanId.value
  const date = pendingNoteDate.value
  const content = pendingNoteContent.value
  if (!planId || date > today) return
  noteState.value = 'saving'
  try {
    const updated = await saveActionNote(planId, date, content)
    if (activePlan.value?.id === planId && selectedDate.value === date) {
      replacePlan(updated)
      noteState.value = 'saved'
    }
  } catch {
    if (activePlan.value?.id === planId && selectedDate.value === date) noteState.value = 'error'
  } finally {
    pendingNotePlanId.value = ''
    pendingNoteDate.value = ''
    pendingNoteContent.value = ''
  }
}

function replacePlan(updated: ActionPlan) {
  plans.value = plans.value.map((plan) => plan.id === updated.id ? updated : plan)
}

function emptyForm() {
  const saved = localStorage.getItem(DRAFT_KEY)
  if (saved) {
    try { Object.assign(form, JSON.parse(saved) as SaveActionPlan); return } catch { /* 丢弃损坏草稿。 */ }
  }
  Object.assign(form, {
    title: '', vision: '', startDate: today, targetDate: plusDays(today, 99),
    reminderTime: '20:00', reminderEnabled: false,
    items: [{ title: '', weekdays: [1, 2, 3, 4, 5] }],
  })
}
function openCreate() {
  editingId.value = ''
  emptyForm()
  editorOpen.value = true
}
function openEdit() {
  const plan = activePlan.value
  if (!plan) return
  editingId.value = plan.id
  Object.assign(form, {
    title: plan.title, vision: plan.vision, startDate: plan.startDate, targetDate: plan.targetDate,
    reminderTime: plan.reminderTime?.slice(0, 5) ?? '20:00', reminderEnabled: plan.reminderEnabled,
    items: plan.items.filter((item) => item.active)
      .map((item) => ({ id: item.id, title: item.title, weekdays: [...item.weekdays] })),
  })
  editorOpen.value = true
}
watch(form, () => {
  if (editorOpen.value && !editingId.value) localStorage.setItem(DRAFT_KEY, JSON.stringify(form))
}, { deep: true })
function addItem() {
  if (form.items.length < 20) form.items.push({ title: '', weekdays: [1, 2, 3, 4, 5] })
}
function removeItem(index: number) {
  if (form.items.length > 1) form.items.splice(index, 1)
}
function toggleWeekday(item: ItemForm, day: number) {
  item.weekdays = item.weekdays.includes(day)
    ? item.weekdays.filter((value) => value !== day)
    : [...item.weekdays, day].sort()
}
function editorError() {
  if (!form.title.trim()) return '请写下你真正想达成的目标'
  if (!form.startDate || !form.targetDate || form.targetDate < form.startDate) return '请设置有效的开始和目标日期'
  if (!form.items.length || form.items.some((item) => !item.title.trim() || !item.weekdays.length)) return '每条行动都要有名称并至少选择一天'
  if (form.reminderEnabled && !form.reminderTime) return '开启提醒后请选择时间'
  return ''
}
async function submit() {
  const error = editorError()
  if (error) { toast(error, 'ri-information-line'); return }
  editorBusy.value = true
  const payload: SaveActionPlan = {
    ...form,
    title: form.title.trim(), vision: form.vision.trim(),
    reminderTime: form.reminderEnabled ? form.reminderTime : null,
    items: form.items.map((item) => ({ ...item, title: item.title.trim() })),
  }
  try {
    const updated = editingId.value
      ? await updateActionPlan(editingId.value, payload)
      : await createActionPlan(payload)
    activePlanId.value = updated.id
    await load()
    editorOpen.value = false
    localStorage.removeItem(DRAFT_KEY)
    toast(editingId.value ? '计划已保存，历史打卡未受影响' : '行动计划已创建，从今天开始执行', 'ri-check-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '计划保存失败，请重试', 'ri-error-warning-line')
  } finally {
    editorBusy.value = false
  }
}
async function togglePlanStatus() {
  const plan = activePlan.value
  if (!plan || statusBusy.value) return
  const completed = plan.status !== 'COMPLETED'
  if (completed && !window.confirm('确认结束这个计划？历史打卡和复盘会保留，也可以随时重新开始。')) return
  statusBusy.value = true
  try {
    replacePlan(await setActionPlanCompleted(plan.id, completed))
    toast(completed ? '计划已完成，记录已保留' : '计划已重新开始', 'ri-flag-line')
  } catch (error) {
    toast(error instanceof Error ? error.message : '状态保存失败', 'ri-error-warning-line')
  } finally { statusBusy.value = false }
}

onMounted(() => void load(false))
onBeforeUnmount(() => { if (noteTimer) { clearTimeout(noteTimer); void flushNote() } })
</script>

<template>
  <main class="action-page">
    <header class="page-head">
      <div>
        <div class="eyebrow">LONG-TERM ACTION</div>
        <h1>行动计划</h1>
        <p>把想成为的样子，拆成今天做得完的动作。</p>
      </div>
      <button class="primary" type="button" @click="openCreate"><i class="ri-add-line"></i>新建计划</button>
    </header>

    <div v-if="loading" class="state-card"><i class="ri-loader-4-line spin"></i>正在读取你的行动计划…</div>
    <div v-else-if="loadError" class="state-card error">
      <i class="ri-error-warning-line"></i><b>计划暂时没读出来</b><span>{{ loadError }}</span>
      <button type="button" @click="load()">重试</button>
    </div>
    <section v-else-if="!plans.length" class="empty-state">
      <div class="empty-orbit"><i class="ri-footprint-line"></i></div>
      <div class="eyebrow">START SMALL · STAY REAL</div>
      <h2>先定方向，再安排第一周</h2>
      <p>系统不会凭一张照片替你编训练处方。写下长期目标，安排你能确认安全、愿意持续的行动，WAO 负责每天提醒、真实打卡和回看。</p>
      <button class="primary large" type="button" @click="openCreate">创建我的第一个行动计划</button>
    </section>

    <template v-else>
      <nav class="plan-tabs" aria-label="行动计划列表">
        <button v-for="plan in plans" :key="plan.id" type="button" :class="{ on: plan.id === activePlan?.id }" @click="activePlanId = plan.id">
          <i :class="plan.status === 'COMPLETED' ? 'ri-checkbox-circle-fill' : 'ri-flag-2-line'"></i>
          <span>{{ plan.title }}</span><small>{{ plan.status === 'COMPLETED' ? '已完成' : `${plan.currentStreak} 天连胜` }}</small>
        </button>
      </nav>

      <section v-if="activePlan" class="plan-hero" :class="{ completed: activePlan.status === 'COMPLETED' }">
        <div class="plan-copy">
          <span class="status-pill"><i class="ri-pulse-line"></i>{{ activePlan.status === 'COMPLETED' ? '计划已完成' : '正在行动' }}</span>
          <h2>{{ activePlan.title }}</h2>
          <p>{{ activePlan.vision || '暂未填写目标愿景；打开设置可补充你想成为的样子和判断标准。' }}</p>
          <div class="plan-meta">
            <span><i class="ri-calendar-event-line"></i>{{ activePlan.startDate }} → {{ activePlan.targetDate }}</span>
            <span :class="{ failed: activePlan.reminderState === 'FAILED' }"><i class="ri-notification-3-line"></i>{{ reminderCopy }}</span>
          </div>
        </div>
        <div class="hero-stats">
          <div><b>{{ activePlan.currentStreak }}</b><span>连续完成天数</span></div>
          <div><b>{{ activePlan.totalCompletedDays }}</b><span>累计完成天数</span></div>
        </div>
        <div class="hero-actions">
          <button type="button" @click="openEdit"><i class="ri-settings-4-line"></i>设置计划</button>
          <button type="button" @click="togglePlanStatus"><i :class="activePlan.status === 'COMPLETED' ? 'ri-restart-line' : 'ri-flag-line'"></i>{{ activePlan.status === 'COMPLETED' ? '重新开始' : '完成计划' }}</button>
        </div>
      </section>

      <section v-if="activePlan" class="day-card">
        <div class="day-head">
          <button type="button" aria-label="前一天" @click="shiftDate(-1)"><i class="ri-arrow-left-s-line"></i></button>
          <div><b>{{ dateLabel }}</b><span>{{ selectedDate }}</span></div>
          <button type="button" aria-label="后一天" :disabled="selectedDate >= today" @click="shiftDate(1)"><i class="ri-arrow-right-s-line"></i></button>
          <button v-if="selectedDate !== today" class="today-button" type="button" @click="selectToday">回到今天</button>
          <div class="day-progress" :class="{ done: allDone }">
            <b>{{ activePlan.completedCount }} / {{ activePlan.scheduledCount }}</b><span>{{ allDone ? '今日已完成' : '当日行动' }}</span>
          </div>
        </div>

        <div v-if="activePlan.scheduledCount" class="action-list">
          <button v-for="item in activePlan.items.filter((entry) => entry.scheduled)" :key="item.id" type="button" class="action-row" :class="{ done: item.completed, busy: busyItemId === item.id }" :disabled="Boolean(busyItemId)" @click="toggle(item.id)">
            <span class="check"><i :class="item.completed ? 'ri-check-line' : 'ri-footprint-line'"></i></span>
            <span><b>{{ item.title }}</b><small>{{ item.completed ? '已真实记录' : '点一下，完成这一天的这一步' }}</small></span>
            <i v-if="busyItemId === item.id" class="ri-loader-4-line spin"></i>
          </button>
        </div>
        <div v-else class="rest-day"><i class="ri-moon-clear-line"></i><b>今天没有安排动作</b><span>休息也是计划的一部分；需要调整节奏可打开“设置计划”。</span></div>

        <div class="note-block">
          <div class="note-title"><span><i class="ri-quill-pen-line"></i>当天复盘</span>
            <small v-if="noteState === 'saving'">保存中…</small><small v-else-if="noteState === 'saved'" class="saved">已保存</small>
            <button v-else-if="noteState === 'error'" type="button" @click="flushNote">保存失败 · 重试</button>
          </div>
          <textarea v-model="noteDraft" maxlength="2000" placeholder="只写事实：今天做了什么、哪里卡住、明天怎么调整。"></textarea>
        </div>
      </section>
    </template>

    <template v-if="editorOpen">
      <div class="mask" @click="!editorBusy && (editorOpen = false)"></div>
      <section class="editor" role="dialog" aria-modal="true" aria-labelledby="action-editor-title">
        <header><div><small>{{ editingId ? '调整执行节奏' : '建立长期方向' }}</small><h2 id="action-editor-title">{{ editingId ? '设置行动计划' : '新建行动计划' }}</h2></div><button type="button" @click="editorOpen = false"><i class="ri-close-line"></i></button></header>
        <div class="editor-body">
          <label><span>我想达成</span><input v-model="form.title" maxlength="128" placeholder="例如：100 天健康重启" /></label>
          <label><span>想成为的样子与判断标准</span><textarea v-model="form.vision" maxlength="1000" rows="3" placeholder="写自己的目标和可观察结果；不要只写一个模糊数字。"></textarea></label>
          <div class="date-grid"><label><span>开始日期</span><input v-model="form.startDate" type="date" /></label><label><span>目标日期</span><input v-model="form.targetDate" type="date" /></label></div>
          <div class="section-title"><div><b>周期行动</b><span>写你确认适合自己的动作；后续调整不会抹掉过去打卡。</span></div><button type="button" @click="addItem"><i class="ri-add-line"></i>添加行动</button></div>
          <div class="item-editor" v-for="(item, index) in form.items" :key="item.id ?? index">
            <div class="item-input"><span>{{ index + 1 }}</span><input v-model="item.title" maxlength="128" placeholder="例如：按自己的训练方案完成今日动作" /><button type="button" :disabled="form.items.length === 1" aria-label="删除行动" @click="removeItem(index)"><i class="ri-delete-bin-line"></i></button></div>
            <div class="weekdays"><span>每周</span><button v-for="day in WEEKDAYS" :key="day.value" type="button" :class="{ on: item.weekdays.includes(day.value) }" @click="toggleWeekday(item, day.value)">{{ day.label }}</button></div>
          </div>
          <div class="reminder-settings">
            <label class="toggle"><input v-model="form.reminderEnabled" type="checkbox" /><span><b>飞书私聊提醒</b><small>只发给当前账号绑定的飞书；没有真实绑定或应用配置时会明确失败。</small></span></label>
            <input v-if="form.reminderEnabled" v-model="form.reminderTime" type="time" aria-label="提醒时间" />
          </div>
          <p class="safety"><i class="ri-shield-check-line"></i>WAO 负责规划、提醒和记录，不根据外形照片生成医疗、饮食或训练处方；疼痛或健康风险请向合格专业人士确认。</p>
        </div>
        <footer><span>{{ !editingId ? '新建草稿会自动保存在本机' : '保存后立即在所有设备生效' }}</span><button type="button" @click="editorOpen = false">取消</button><button class="primary" type="button" :disabled="editorBusy" @click="submit">{{ editorBusy ? '保存中…' : '保存计划' }}</button></footer>
      </section>
    </template>
  </main>
</template>

<style scoped>
.action-page { max-width: none; margin: 0 auto; padding: 24px 28px 52px; color: var(--wao-text); }
.page-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; }
.eyebrow { color: #267c65; font-size: 10px; font-weight: 750; letter-spacing: 2.2px; }
h1 { margin: 5px 0 1px; font-size: 25px; } .page-head p { margin: 0; color: var(--wao-text-weak); font-size: 13px; }
button { font: inherit; } .primary { min-height: 36px; border: 0; border-radius: 9px; padding: 0 15px; background: #267c65; color: #fff; cursor: pointer; display: inline-flex; align-items: center; gap: 6px; }
.primary:hover { background: #1e6754; } .primary:disabled { opacity: .55; cursor: wait; } .primary.large { min-height: 42px; padding: 0 20px; font-weight: 650; }
.state-card { margin-top: 20px; min-height: 180px; border: 1px solid var(--wao-line); border-radius: 16px; background: var(--wao-bg-card); display: flex; align-items: center; justify-content: center; gap: 9px; color: var(--wao-text-weak); }
.state-card.error { flex-direction: column; } .state-card.error b { color: var(--wao-text-2); } .state-card.error button { border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-card); padding: 6px 13px; cursor: pointer; }
.spin { animation: spin .8s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
.empty-state { margin-top: 20px; min-height: 460px; padding: 60px 20px; border: 1px solid var(--wao-line); border-radius: 20px; background: radial-gradient(circle at 50% 20%, #e7f6ef 0, transparent 34%), var(--wao-bg-card); text-align: center; display: flex; flex-direction: column; align-items: center; }
.empty-orbit { width: 88px; height: 88px; margin-bottom: 22px; border: 1px solid #9dd3be; border-radius: 50%; display: grid; place-items: center; box-shadow: 0 0 0 15px rgba(118, 197, 165, .10), 0 0 0 30px rgba(118, 197, 165, .05); }
.empty-orbit i { font-size: 34px; color: #267c65; } .empty-state h2 { margin: 8px 0 7px; font-size: 24px; } .empty-state p { max-width: 560px; margin: 0 0 24px; color: var(--wao-text-weak); line-height: 1.8; font-size: 13px; }
.plan-tabs { margin-top: 20px; display: flex; gap: 8px; overflow-x: auto; padding-bottom: 4px; }
.plan-tabs button { min-width: 175px; height: 52px; border: 1px solid var(--wao-line); border-radius: 11px; background: var(--wao-bg-card); color: var(--wao-text-3); cursor: pointer; padding: 7px 11px; display: grid; grid-template-columns: auto 1fr; column-gap: 8px; text-align: left; }
.plan-tabs button > i { grid-row: 1 / 3; align-self: center; color: var(--wao-text-faint); font-size: 18px; }.plan-tabs button span { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 12.5px; font-weight: 650; }.plan-tabs button small { font-size: 10.5px; color: var(--wao-text-faint); }
.plan-tabs button.on { border-color: #89bfae; background: #edf8f3; color: #185c49; }.plan-tabs button.on > i { color: #267c65; }
.plan-hero { margin-top: 12px; min-height: 185px; border-radius: 18px; padding: 24px 26px; background: linear-gradient(125deg, #173f36 0%, #286c59 60%, #3d8d70 100%); color: #fff; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 18px; position: relative; overflow: hidden; }
.plan-hero::after { content: ''; position: absolute; width: 260px; height: 260px; right: -80px; top: -100px; border: 1px solid rgba(255,255,255,.13); border-radius: 50%; box-shadow: 0 0 0 35px rgba(255,255,255,.035); }.plan-hero.completed { filter: saturate(.65); }
.plan-copy { position: relative; z-index: 1; }.status-pill { display: inline-flex; gap: 5px; align-items: center; border-radius: 999px; padding: 4px 9px; background: rgba(255,255,255,.13); font-size: 10.5px; }.plan-copy h2 { margin: 10px 0 5px; font-size: 25px; }.plan-copy > p { max-width: 670px; margin: 0; color: rgba(255,255,255,.76); font-size: 12.5px; line-height: 1.65; white-space: pre-wrap; }
.plan-meta { margin-top: 15px; display: flex; gap: 16px; flex-wrap: wrap; color: rgba(255,255,255,.72); font-size: 11px; }.plan-meta span { display: inline-flex; align-items: center; gap: 5px; }.plan-meta .failed { color: #ffd1c8; }
.hero-stats { align-self: center; display: flex; gap: 10px; position: relative; z-index: 1; }.hero-stats div { width: 105px; height: 92px; border-radius: 14px; background: rgba(255,255,255,.11); backdrop-filter: blur(5px); display: flex; flex-direction: column; align-items: center; justify-content: center; }.hero-stats b { font-size: 28px; font-variant-numeric: tabular-nums; }.hero-stats span { color: rgba(255,255,255,.68); font-size: 10px; }
.hero-actions { grid-column: 1 / -1; display: flex; gap: 8px; position: relative; z-index: 1; }.hero-actions button { height: 31px; border: 1px solid rgba(255,255,255,.22); border-radius: 8px; background: rgba(255,255,255,.08); color: #fff; padding: 0 11px; cursor: pointer; display: inline-flex; align-items: center; gap: 5px; font-size: 11.5px; }
.day-card { margin-top: 14px; border: 1px solid var(--wao-line); border-radius: 16px; background: var(--wao-bg-card); overflow: hidden; }.day-head { min-height: 70px; padding: 11px 16px; display: flex; align-items: center; gap: 8px; border-bottom: 1px solid var(--wao-divider); }.day-head > button:not(.today-button) { width: 30px; height: 30px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); cursor: pointer; }.day-head > button:disabled { opacity: .35; }.day-head > div:nth-of-type(1) { display: grid; min-width: 105px; }.day-head b { font-size: 14px; }.day-head span { color: var(--wao-text-weak); font-size: 10.5px; }.today-button { border: 0; background: none; color: #267c65; cursor: pointer; font-size: 11px; }.day-progress { margin-left: auto; min-width: 105px; display: grid; text-align: right; }.day-progress b { color: #936119; font-size: 16px; }.day-progress.done b { color: #267c65; }
.action-list { padding: 9px 12px; display: grid; gap: 5px; }.action-row { width: 100%; min-height: 58px; border: 1px solid transparent; border-radius: 10px; background: transparent; color: var(--wao-text); padding: 7px 10px; display: flex; align-items: center; gap: 11px; text-align: left; cursor: pointer; }.action-row:hover { background: var(--wao-bg-hover); }.action-row.done { background: var(--wao-success-bg); color: var(--wao-success-strong); }.action-row.busy { opacity: .6; }.check { width: 31px; height: 31px; border: 1.5px solid var(--wao-success-line); border-radius: 10px; display: grid; place-items: center; color: var(--wao-success-strong); flex-shrink: 0; }.action-row.done .check { border-color: var(--wao-success); background: var(--wao-success); color: #fff; }.action-row > span:nth-child(2) { flex: 1; min-width: 0; display: grid; }.action-row b { font-size: 13px; }.action-row.done b { text-decoration: line-through; text-decoration-color: var(--wao-success-line); }.action-row small { margin-top: 2px; color: var(--wao-text-faint); font-size: 10.5px; }
.rest-day { min-height: 130px; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--wao-text-weak); }.rest-day i { margin-bottom: 7px; color: #749588; font-size: 24px; }.rest-day b { color: var(--wao-text-2); font-size: 13px; }.rest-day span { margin-top: 3px; font-size: 11px; }
.note-block { border-top: 1px solid var(--wao-divider); padding: 13px 16px 16px; }.note-title { display: flex; align-items: center; gap: 8px; }.note-title > span { font-size: 12px; font-weight: 650; }.note-title small, .note-title button { margin-left: auto; border: 0; background: none; color: var(--wao-text-faint); font-size: 10.5px; }.note-title .saved { color: #267c65; }.note-title button { color: #b13e3e; cursor: pointer; }.note-block textarea { width: 100%; min-height: 76px; margin-top: 8px; border: 1px solid var(--wao-line-control); border-radius: 9px; padding: 9px 10px; background: var(--wao-bg-page); color: var(--wao-text); resize: vertical; outline: none; font-size: 12.5px; line-height: 1.6; }.note-block textarea:focus { border-color: #73ae9b; box-shadow: 0 0 0 3px rgba(79,154,128,.10); }
.mask { position: fixed; inset: 0; z-index: 100; background: rgba(18, 29, 26, .42); }.editor { position: fixed; z-index: 101; top: 5vh; left: 50%; transform: translateX(-50%); width: min(650px, calc(100vw - 30px)); max-height: 90vh; border-radius: 16px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-modal); display: flex; flex-direction: column; overflow: hidden; }.editor header { min-height: 69px; padding: 13px 19px; border-bottom: 1px solid var(--wao-divider); display: flex; align-items: center; }.editor header small { color: #267c65; font-size: 10px; }.editor header h2 { margin: 2px 0 0; font-size: 17px; }.editor header button { margin-left: auto; width: 31px; height: 31px; border: 0; border-radius: 8px; background: transparent; cursor: pointer; }.editor-body { padding: 16px 19px 20px; overflow-y: auto; }.editor-body > label { display: grid; gap: 5px; margin-bottom: 13px; }.editor-body label > span, .date-grid label > span { color: var(--wao-text-weak); font-size: 11.5px; }.editor input, .editor textarea { border: 1px solid var(--wao-line-control); border-radius: 8px; padding: 8px 10px; background: var(--wao-bg-card); color: var(--wao-text); outline: none; font-size: 13px; }.editor input:focus, .editor textarea:focus { border-color: #73ae9b; box-shadow: 0 0 0 3px rgba(79,154,128,.10); }.editor textarea { resize: vertical; }.date-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }.date-grid label { display: grid; gap: 5px; }
.section-title { margin: 18px 0 8px; padding-top: 14px; border-top: 1px solid var(--wao-divider); display: flex; align-items: center; }.section-title > div { display: grid; }.section-title b { font-size: 13px; }.section-title span { margin-top: 2px; color: var(--wao-text-faint); font-size: 10.5px; }.section-title button { margin-left: auto; height: 29px; border: 1px solid var(--wao-line-control); border-radius: 7px; background: var(--wao-bg-card); color: #267c65; cursor: pointer; font-size: 11px; }
.item-editor { margin-top: 8px; padding: 10px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-page); }.item-input { display: flex; align-items: center; gap: 7px; }.item-input > span { width: 22px; height: 22px; border-radius: 7px; background: #ddefe7; color: #267c65; display: grid; place-items: center; font-size: 10px; }.item-input input { flex: 1; background: var(--wao-bg-card); }.item-input button { width: 29px; height: 29px; border: 0; background: transparent; color: var(--wao-text-faint); cursor: pointer; }.item-input button:disabled { opacity: .2; }.weekdays { margin-top: 8px; display: flex; align-items: center; gap: 5px; }.weekdays > span { margin-right: 2px; color: var(--wao-text-faint); font-size: 10.5px; }.weekdays button { width: 28px; height: 28px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-weak); cursor: pointer; font-size: 11px; }.weekdays button.on { border-color: #73ae9b; background: #ddefe7; color: #1f6953; font-weight: 650; }
.reminder-settings { margin-top: 15px; padding: 12px; border-radius: 10px; background: var(--wao-success-bg); display: flex; align-items: center; gap: 12px; }.toggle { flex: 1; display: flex; align-items: center; gap: 9px; }.toggle > input { width: 16px; height: 16px; accent-color: var(--wao-success); }.toggle > span { display: grid; }.toggle b { color: var(--wao-success-strong); font-size: 12px; }.toggle small { margin-top: 2px; color: var(--wao-text-3); font-size: 10px; }.reminder-settings > input { width: 115px; background: var(--wao-bg-card); color: var(--wao-text); }.safety { margin: 13px 0 0; color: var(--wao-text-faint); font-size: 10.5px; line-height: 1.55; }.safety i { margin-right: 4px; color: var(--wao-success); }
.editor footer { min-height: 61px; padding: 10px 19px; border-top: 1px solid var(--wao-divider); display: flex; align-items: center; gap: 8px; }.editor footer > span { flex: 1; color: var(--wao-text-faint); font-size: 10.5px; }.editor footer > button:not(.primary) { height: 34px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-3); padding: 0 14px; cursor: pointer; }
@media (max-width: 760px) {
  .action-page { padding: 16px 12px 92px; }.page-head { align-items: center; }.page-head p { display: none; }.page-head .primary { padding: 0 11px; }.plan-tabs { margin-top: 14px; }.plan-hero { padding: 20px 17px; grid-template-columns: 1fr; }.hero-stats { justify-content: stretch; }.hero-stats div { flex: 1; width: auto; }.day-head { flex-wrap: wrap; }.day-progress { min-width: 86px; }.action-list { padding: 7px; }.action-row { padding: 7px 5px; }.editor { top: 12px; width: calc(100vw - 20px); max-height: calc(100dvh - 24px); }.editor-body { padding: 13px; }.date-grid { grid-template-columns: 1fr; }.weekdays { gap: 3px; }.weekdays button { flex: 1; width: auto; }.reminder-settings { align-items: flex-start; flex-direction: column; }.editor footer > span { display: none; }.editor footer { justify-content: flex-end; }
}
</style>
