<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  fetchFoodWorkspace,
  saveFoodWorkspace,
  type FoodDayType,
  type FoodMealLog,
  type FoodMealTemplate,
  type FoodPreparationTask,
  type FoodTaskType,
  type FoodWorkspace,
  type MealComfort,
  type MealType,
} from '@/api/food'
import { toast } from '@/composables/toast'

type Section = 'today' | 'templates' | 'logs' | 'observations' | 'preparation'
type EditorKind = 'template' | 'log' | 'task' | ''
type SaveState = 'idle' | 'saving' | 'saved' | 'error'

const WORKSPACE_DRAFT_KEY = 'wao-food-workspace-draft-v1'
const FORM_DRAFT_KEY = 'wao-food-form-draft-v1'
const today = isoDate(new Date())
const todayDayType: FoodDayType = isWeekday(today) ? 'WORKDAY' : 'RESTDAY'
const sections: Array<{ id: Section; label: string; icon: string }> = [
  { id: 'today', label: '今日三餐', icon: 'ri-bowl-line' },
  { id: 'templates', label: '工作日与休息日', icon: 'ri-calendar-schedule-line' },
  { id: 'logs', label: '实际记录', icon: 'ri-file-list-3-line' },
  { id: 'observations', label: '我的观察', icon: 'ri-heart-pulse-line' },
  { id: 'preparation', label: '采购与备餐', icon: 'ri-shopping-basket-2-line' },
]
const meals: Array<{ value: MealType; label: string; icon: string }> = [
  { value: 'BREAKFAST', label: '早餐', icon: 'ri-sun-line' },
  { value: 'LUNCH', label: '午餐', icon: 'ri-sun-cloudy-line' },
  { value: 'DINNER', label: '晚餐', icon: 'ri-moon-clear-line' },
]
const dayTypes: Array<{ value: FoodDayType; label: string }> = [
  { value: 'WORKDAY', label: '工作日' },
  { value: 'RESTDAY', label: '休息日' },
]
const comforts: Array<{ value: MealComfort; label: string; icon: string }> = [
  { value: 'COMFORTABLE', label: '舒服', icon: 'ri-emotion-happy-line' },
  { value: 'NEUTRAL', label: '一般', icon: 'ri-emotion-normal-line' },
  { value: 'UNCOMFORTABLE', label: '不舒服', icon: 'ri-emotion-unhappy-line' },
]

const loading = ref(true)
const loadError = ref('')
const activeSection = ref<Section>('today')
const workspace = ref<FoodWorkspace>(emptyWorkspace())
const initialized = ref(false)
const saveState = ref<SaveState>('idle')
const saveError = ref('')
const editorKind = ref<EditorKind>('')
const editingId = ref('')
const logDayFilter = ref<FoodDayType | 'ALL'>('ALL')
let saveTimer: ReturnType<typeof setTimeout> | null = null
let applyingServer = false

const templateForm = reactive({
  dayType: todayDayType,
  mealType: 'BREAKFAST' as MealType,
  title: '',
  targetTime: '08:00',
  foods: '',
  prepMinutes: 10,
  note: '',
  enabled: true,
})
const logForm = reactive({
  date: today,
  dayType: todayDayType,
  mealType: currentMealType(),
  eatenAt: currentTime(),
  foods: '',
  prepMinutes: 0,
  comfort: 'NEUTRAL' as MealComfort,
  qualityScore: 3,
  observation: '',
})
const taskForm = reactive({
  type: 'GROCERY' as FoodTaskType,
  title: '',
  dueDate: today,
  note: '',
})

const todayLogs = computed(() => workspace.value.mealLogs.filter((item) => item.date === today))
const recentLogs = computed(() => {
  const from = isoDate(new Date(Date.now() - 6 * 86_400_000))
  return workspace.value.mealLogs.filter((item) => item.date >= from && item.date <= today)
})
const filteredLogs = computed(() =>
  workspace.value.mealLogs.filter(
    (item) => logDayFilter.value === 'ALL' || item.dayType === logDayFilter.value,
  ),
)
const averagePrep = computed(() => {
  if (!recentLogs.value.length) return null
  return Math.round(
    recentLogs.value.reduce((sum, item) => sum + item.prepMinutes, 0) / recentLogs.value.length,
  )
})
const averageQuality = computed(() => {
  if (!recentLogs.value.length) return null
  return (
    recentLogs.value.reduce((sum, item) => sum + item.qualityScore, 0) / recentLogs.value.length
  ).toFixed(1)
})
const openTasks = computed(() =>
  workspace.value.preparationTasks
    .filter((item) => item.status === 'OPEN')
    .sort((a, b) => a.dueDate.localeCompare(b.dueDate)),
)
const overdueTasks = computed(() => openTasks.value.filter((item) => item.dueDate < today))
const foodObservations = computed(() => {
  const facts = new Map<
    string,
    { name: string; total: number; comfortable: number; uncomfortable: number }
  >()
  for (const log of workspace.value.mealLogs) {
    for (const food of log.foods) {
      const key = food.trim().toLocaleLowerCase()
      const fact = facts.get(key) ?? {
        name: food.trim(),
        total: 0,
        comfortable: 0,
        uncomfortable: 0,
      }
      fact.total += 1
      if (log.comfort === 'COMFORTABLE') fact.comfortable += 1
      if (log.comfort === 'UNCOMFORTABLE') fact.uncomfortable += 1
      facts.set(key, fact)
    }
  }
  return [...facts.values()].sort(
    (a, b) =>
      b.total - a.total || b.uncomfortable - a.uncomfortable || a.name.localeCompare(b.name),
  )
})
const modalTitle = computed(
  () =>
    ({
      template: editingId.value ? '编辑餐次模板' : '新增餐次模板',
      log: editingId.value ? '编辑实际记录' : '记录实际吃了什么',
      task: editingId.value ? '编辑备餐事项' : '新增采购或备餐事项',
      '': '',
    })[editorKind.value],
)

function emptyWorkspace(): FoodWorkspace {
  return {
    templates: [],
    mealLogs: [],
    preparationTasks: [],
    profile: { preferences: '', avoidances: '', stomachObservations: '', efficiencyGoal: '' },
    principles:
      '饮食有节，按自己的日常节律安排三餐；少做临时决定，吃后如实记录，再用长期观察调整。',
    updatedAt: null,
  }
}
function isoDate(date: Date) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(date)
}
function isWeekday(value: string) {
  const day = new Date(`${value}T12:00:00+08:00`).getUTCDay()
  return day >= 1 && day <= 5
}
function currentTime() {
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: 'Asia/Shanghai',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date())
}
function currentMealType(): MealType {
  const hour = Number(currentTime().slice(0, 2))
  return hour < 10 ? 'BREAKFAST' : hour < 15 ? 'LUNCH' : 'DINNER'
}
function id(prefix: string) {
  return `${prefix}${crypto.randomUUID().replace(/-/g, '').slice(0, 24)}`
}
function splitFoods(value: string) {
  return [
    ...new Set(
      value
        .split(/[,，、\n]/)
        .map((item) => item.trim())
        .filter(Boolean),
    ),
  ].slice(0, 30)
}
function joinFoods(value: string[]) {
  return value.join('、')
}
function dayLabel(value: FoodDayType) {
  return dayTypes.find((item) => item.value === value)?.label ?? value
}
function mealLabel(value: MealType) {
  return meals.find((item) => item.value === value)?.label ?? value
}
function comfortLabel(value: MealComfort) {
  return comforts.find((item) => item.value === value)?.label ?? value
}
function timeLabel(value: string) {
  return value.slice(0, 5)
}
function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error && error.message ? error.message : fallback
}
function readJson<T>(key: string): T | null {
  try {
    const raw = localStorage.getItem(key)
    return raw ? (JSON.parse(raw) as T) : null
  } catch {
    return null
  }
}
function writeWorkspaceDraft() {
  try {
    localStorage.setItem(WORKSPACE_DRAFT_KEY, JSON.stringify(workspace.value))
  } catch {
    /* 当前页面内存仍保留。 */
  }
}
function clearWorkspaceDraft() {
  try {
    localStorage.removeItem(WORKSPACE_DRAFT_KEY)
  } catch {
    /* 忽略。 */
  }
}
async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const server = await fetchFoodWorkspace()
    const draft = readJson<FoodWorkspace>(WORKSPACE_DRAFT_KEY)
    applyingServer = true
    workspace.value = draft ?? server
    await nextTick()
    applyingServer = false
    initialized.value = true
    if (draft) {
      saveState.value = 'error'
      saveError.value = '已恢复上次未同步的本机草稿，请重试保存。'
    }
  } catch (error) {
    const draft = readJson<FoodWorkspace>(WORKSPACE_DRAFT_KEY)
    if (draft) {
      applyingServer = true
      workspace.value = draft
      await nextTick()
      applyingServer = false
      initialized.value = true
      saveState.value = 'error'
      saveError.value = '服务端暂时不可用，当前显示本机草稿。'
    } else loadError.value = errorMessage(error, '饮食管理加载失败')
  } finally {
    loading.value = false
  }
}
function queueSave() {
  if (!initialized.value) return
  writeWorkspaceDraft()
  saveState.value = 'saving'
  saveError.value = ''
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => void persist(), 700)
}
async function persist() {
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = null
  const payload = JSON.parse(JSON.stringify(workspace.value)) as FoodWorkspace
  const fingerprint = JSON.stringify(payload)
  saveState.value = 'saving'
  saveError.value = ''
  try {
    const saved = await saveFoodWorkspace(payload)
    if (JSON.stringify(workspace.value) === fingerprint) {
      applyingServer = true
      workspace.value = saved
      await nextTick()
      applyingServer = false
      clearWorkspaceDraft()
      saveState.value = 'saved'
    } else {
      workspace.value.updatedAt = saved.updatedAt
      queueSave()
    }
  } catch (error) {
    writeWorkspaceDraft()
    saveState.value = 'error'
    saveError.value = errorMessage(error, '保存失败，草稿已保留')
  }
}
function resetForms() {
  Object.assign(templateForm, {
    dayType: todayDayType,
    mealType: 'BREAKFAST',
    title: '',
    targetTime: '08:00',
    foods: '',
    prepMinutes: 10,
    note: '',
    enabled: true,
  })
  Object.assign(logForm, {
    date: today,
    dayType: todayDayType,
    mealType: currentMealType(),
    eatenAt: currentTime(),
    foods: '',
    prepMinutes: 0,
    comfort: 'NEUTRAL',
    qualityScore: 3,
    observation: '',
  })
  Object.assign(taskForm, { type: 'GROCERY', title: '', dueDate: today, note: '' })
}
function editorForm() {
  if (editorKind.value === 'template') return templateForm
  if (editorKind.value === 'log') return logForm
  if (editorKind.value === 'task') return taskForm
  return null
}
function openCreate(kind: Exclude<EditorKind, ''>, mealType?: MealType) {
  resetForms()
  editingId.value = ''
  editorKind.value = kind
  if (mealType && kind === 'log') logForm.mealType = mealType
  const draft = readJson<{ kind: EditorKind; editingId?: string; form: Record<string, unknown> }>(
    FORM_DRAFT_KEY,
  )
  if (draft?.kind === kind && !draft.editingId) Object.assign(editorForm()!, draft.form)
}
function editTemplate(item: FoodMealTemplate) {
  resetForms()
  editorKind.value = 'template'
  editingId.value = item.id
  Object.assign(templateForm, {
    ...item,
    targetTime: timeLabel(item.targetTime),
    foods: joinFoods(item.foods),
  })
}
function editLog(item: FoodMealLog) {
  resetForms()
  editorKind.value = 'log'
  editingId.value = item.id
  Object.assign(logForm, {
    ...item,
    eatenAt: timeLabel(item.eatenAt),
    foods: joinFoods(item.foods),
  })
}
function editTask(item: FoodPreparationTask) {
  resetForms()
  editorKind.value = 'task'
  editingId.value = item.id
  Object.assign(taskForm, item)
}
function closeEditor(clearDraft = false) {
  if (clearDraft)
    try {
      localStorage.removeItem(FORM_DRAFT_KEY)
    } catch {
      /* 忽略。 */
    }
  editorKind.value = ''
  editingId.value = ''
}
function requireText(value: string, message: string) {
  if (value.trim()) return true
  toast(message, 'ri-information-line')
  return false
}
function upsert<T extends { id: string }>(items: T[], value: T) {
  const index = items.findIndex((item) => item.id === value.id)
  if (index >= 0) items.splice(index, 1, value)
  else items.push(value)
}
function submitEditor() {
  if (editorKind.value === 'template') {
    const foods = splitFoods(templateForm.foods)
    if (!requireText(templateForm.title, '请填写模板名称') || !foods.length) {
      if (!foods.length) toast('请至少填写一种食物', 'ri-information-line')
      return
    }
    upsert(workspace.value.templates, {
      id: editingId.value || id('ft'),
      dayType: templateForm.dayType,
      mealType: templateForm.mealType,
      title: templateForm.title.trim(),
      targetTime: templateForm.targetTime,
      foods,
      prepMinutes: Number(templateForm.prepMinutes),
      note: templateForm.note.trim(),
      enabled: templateForm.enabled,
    })
  } else if (editorKind.value === 'log') {
    const foods = splitFoods(logForm.foods)
    if (!foods.length) {
      toast('请至少记录一种实际吃过的食物', 'ri-information-line')
      return
    }
    const duplicate = workspace.value.mealLogs.find(
      (item) =>
        item.id !== editingId.value &&
        item.date === logForm.date &&
        item.mealType === logForm.mealType,
    )
    if (duplicate) {
      toast(`这一天的${mealLabel(logForm.mealType)}已记录，请编辑已有记录`, 'ri-information-line')
      return
    }
    upsert(workspace.value.mealLogs, {
      id: editingId.value || id('fl'),
      date: logForm.date,
      dayType: logForm.dayType,
      mealType: logForm.mealType,
      eatenAt: logForm.eatenAt,
      foods,
      prepMinutes: Number(logForm.prepMinutes),
      comfort: logForm.comfort,
      qualityScore: Number(logForm.qualityScore),
      observation: logForm.observation.trim(),
    })
    workspace.value.mealLogs.sort(
      (a, b) => b.date.localeCompare(a.date) || b.eatenAt.localeCompare(a.eatenAt),
    )
  } else if (editorKind.value === 'task') {
    if (!requireText(taskForm.title, '请填写采购或备餐事项')) return
    upsert(workspace.value.preparationTasks, {
      id: editingId.value || id('fp'),
      type: taskForm.type,
      title: taskForm.title.trim(),
      dueDate: taskForm.dueDate,
      status:
        workspace.value.preparationTasks.find((item) => item.id === editingId.value)?.status ??
        'OPEN',
      note: taskForm.note.trim(),
    })
  }
  queueSave()
  closeEditor(true)
  toast('已记录，正在同步个人饮食档案', 'ri-check-line')
}
function removeTemplate(item: FoodMealTemplate) {
  if (!window.confirm(`确认删除模板“${item.title}”？`)) return
  workspace.value.templates = workspace.value.templates.filter((value) => value.id !== item.id)
  queueSave()
}
function removeLog(item: FoodMealLog) {
  if (!window.confirm(`确认删除 ${item.date} 的${mealLabel(item.mealType)}记录？`)) return
  workspace.value.mealLogs = workspace.value.mealLogs.filter((value) => value.id !== item.id)
  queueSave()
}
function removeTask(item: FoodPreparationTask) {
  if (!window.confirm(`确认删除事项“${item.title}”？`)) return
  workspace.value.preparationTasks = workspace.value.preparationTasks.filter(
    (value) => value.id !== item.id,
  )
  queueSave()
}
function toggleTask(item: FoodPreparationTask) {
  item.status = item.status === 'OPEN' ? 'DONE' : 'OPEN'
  queueSave()
}
function useTemplate(item: FoodMealTemplate) {
  resetForms()
  editorKind.value = 'log'
  Object.assign(logForm, {
    dayType: item.dayType,
    mealType: item.mealType,
    eatenAt: timeLabel(item.targetTime),
    foods: joinFoods(item.foods),
    prepMinutes: item.prepMinutes,
  })
}
function templateFor(mealType: MealType) {
  return workspace.value.templates.find(
    (item) => item.enabled && item.dayType === todayDayType && item.mealType === mealType,
  )
}
function logFor(mealType: MealType) {
  return todayLogs.value.find((item) => item.mealType === mealType)
}
function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'long',
    day: 'numeric',
    weekday: 'short',
  }).format(new Date(`${value}T12:00:00+08:00`))
}

watch(
  workspace,
  () => {
    if (initialized.value && !applyingServer) queueSave()
  },
  { deep: true },
)
watch(
  [templateForm, logForm, taskForm],
  () => {
    const form = editorForm()
    if (!form) return
    try {
      localStorage.setItem(
        FORM_DRAFT_KEY,
        JSON.stringify({ kind: editorKind.value, editingId: editingId.value, form }),
      )
    } catch {
      /* 忽略。 */
    }
  },
  { deep: true },
)
watch(
  () => logForm.date,
  (date) => {
    if (date) logForm.dayType = isWeekday(date) ? 'WORKDAY' : 'RESTDAY'
  },
)
onMounted(load)
onBeforeUnmount(() => {
  if (saveTimer) {
    clearTimeout(saveTimer)
    void persist()
  }
})
</script>

<template>
  <main class="food-page">
    <header class="hero">
      <div>
        <span class="eyebrow">THREE MEALS · TRUE RECORDS</span>
        <h1>饮食管理</h1>
        <p>先为工作日和休息日做好选择，再用每一次真实用餐，找到更舒服、更稳定也更省事的节奏。</p>
        <textarea
          v-model="workspace.principles"
          maxlength="2000"
          aria-label="我的饮食理念"
          :disabled="loading"
        ></textarea>
      </div>
      <aside>
        <i class="ri-leaf-line"></i>
        <strong>饮食有节</strong>
        <span>文化视角 · 自我观察</span>
        <small>不判断体质，不提供药膳、方剂或治疗建议</small>
      </aside>
    </header>

    <div class="save-strip" :class="saveState">
      <i
        :class="
          saveState === 'saving'
            ? 'ri-loader-4-line spin'
            : saveState === 'error'
              ? 'ri-error-warning-line'
              : 'ri-cloud-line'
        "
      ></i>
      <span v-if="saveState === 'saving'">正在保存到个人饮食档案…</span>
      <span v-else-if="saveState === 'error'">{{ saveError }}</span>
      <span v-else-if="saveState === 'saved'">已保存，刷新或换设备仍可读取</span>
      <span v-else>所有记录仅当前成员可见</span>
      <button v-if="saveState === 'error'" type="button" @click="persist">重试保存</button>
    </div>

    <section v-if="loading" class="state-card">
      <i class="ri-loader-4-line spin"></i>正在读取餐次与个人观察…
    </section>
    <section v-else-if="loadError" class="state-card error">
      <i class="ri-error-warning-line"></i><strong>饮食管理加载失败</strong>
      <span>{{ loadError }}</span
      ><button @click="load">重试</button>
    </section>

    <template v-else>
      <section class="metrics">
        <article>
          <span>今日真实记录</span><strong>{{ todayLogs.length }} / 3</strong
          ><small>模板不算已完成</small>
        </article>
        <article>
          <span>近 7 天记录</span><strong>{{ recentLogs.length }}</strong
          ><small>餐次</small>
        </article>
        <article>
          <span>平均准备</span><strong>{{ averagePrep ?? '—' }}</strong
          ><small>{{ averagePrep === null ? '暂无记录' : '分钟 / 餐' }}</small>
        </article>
        <article>
          <span>主观质量</span><strong>{{ averageQuality ?? '—' }}</strong
          ><small>{{ averageQuality === null ? '暂无记录' : '近 7 天 / 5 分' }}</small>
        </article>
      </section>

      <nav class="section-tabs" aria-label="饮食管理分区">
        <button
          v-for="section in sections"
          :key="section.id"
          :class="{ active: activeSection === section.id }"
          @click="activeSection = section.id"
        >
          <i :class="section.icon"></i>{{ section.label }}
        </button>
      </nav>

      <section v-if="activeSection === 'today'" class="content-section">
        <div class="section-heading">
          <div>
            <span>{{ formatDate(today) }} · {{ dayLabel(todayDayType) }}</span>
            <h2>今天实际吃了什么</h2>
          </div>
          <button class="primary" @click="openCreate('log')">
            <i class="ri-add-line"></i>记录一餐
          </button>
        </div>
        <div class="meal-grid">
          <article
            v-for="meal in meals"
            :key="meal.value"
            class="meal-card"
            :class="{ complete: logFor(meal.value) }"
          >
            <header>
              <i :class="meal.icon"></i><strong>{{ meal.label }}</strong
              ><span>{{ logFor(meal.value) ? '已记录' : '待记录' }}</span>
            </header>
            <template v-if="logFor(meal.value)">
              <h3>{{ joinFoods(logFor(meal.value)!.foods) }}</h3>
              <p>
                {{ timeLabel(logFor(meal.value)!.eatenAt) }} · 准备
                {{ logFor(meal.value)!.prepMinutes }} 分钟 ·
                {{ comfortLabel(logFor(meal.value)!.comfort) }}
              </p>
              <small>{{ logFor(meal.value)!.observation || '本餐没有补充观察' }}</small>
              <button class="text-button" @click="editLog(logFor(meal.value)!)">
                编辑真实记录
              </button>
            </template>
            <template v-else-if="templateFor(meal.value)">
              <h3>{{ templateFor(meal.value)!.title }}</h3>
              <p>
                {{ timeLabel(templateFor(meal.value)!.targetTime) }} ·
                {{ joinFoods(templateFor(meal.value)!.foods) }}
              </p>
              <small>这是可执行模板，尚不代表已经吃过</small>
              <button class="text-button" @click="useTemplate(templateFor(meal.value)!)">
                按模板记录
              </button>
            </template>
            <template v-else>
              <h3>还没有安排</h3>
              <p>先记录真实用餐，也可以稍后建立常用模板。</p>
              <button class="text-button" @click="openCreate('log', meal.value)">记录这餐</button>
            </template>
          </article>
        </div>
        <article class="boundary-card">
          <i class="ri-shield-check-line"></i>
          <div>
            <strong>把身体反应当作观察，不当作诊断</strong>
            <p>偶发感受可以继续记录；持续、严重或紧急不适，请咨询合格医务人员。</p>
          </div>
        </article>
      </section>

      <section v-else-if="activeSection === 'templates'" class="content-section">
        <div class="section-heading">
          <div>
            <span>提前做好选择</span>
            <h2>工作日与休息日三餐模板</h2>
          </div>
          <button class="primary" @click="openCreate('template')">
            <i class="ri-add-line"></i>新增模板
          </button>
        </div>
        <div v-if="!workspace.templates.length" class="empty-state">
          <i class="ri-calendar-schedule-line"></i>
          <h3>还没有餐次模板</h3>
          <p>从最容易临时决定的一餐开始，记录常用食物与准备时长。</p>
          <button class="primary" @click="openCreate('template')">建立第一个模板</button>
        </div>
        <div v-else class="day-columns">
          <article v-for="day in dayTypes" :key="day.value">
            <header>
              <div>
                <strong>{{ day.label }}</strong
                ><span
                  >{{
                    workspace.templates.filter((item) => item.dayType === day.value).length
                  }}
                  个模板</span
                >
              </div>
            </header>
            <div
              v-for="item in workspace.templates.filter((value) => value.dayType === day.value)"
              :key="item.id"
              class="list-card"
            >
              <div class="list-icon">
                <i :class="meals.find((meal) => meal.value === item.mealType)?.icon"></i>
              </div>
              <div>
                <div class="tags">
                  <span>{{ mealLabel(item.mealType) }}</span
                  ><span>{{ timeLabel(item.targetTime) }}</span
                  ><span>{{ item.prepMinutes }} 分钟</span>
                </div>
                <h3>{{ item.title }}</h3>
                <p>{{ joinFoods(item.foods) }}</p>
                <small>{{ item.note || (item.enabled ? '用于今日推荐' : '当前已停用') }}</small>
              </div>
              <div class="actions">
                <button title="编辑" @click="editTemplate(item)">
                  <i class="ri-edit-line"></i></button
                ><button title="删除" @click="removeTemplate(item)">
                  <i class="ri-delete-bin-line"></i>
                </button>
              </div>
            </div>
            <p
              v-if="!workspace.templates.some((item) => item.dayType === day.value)"
              class="column-empty"
            >
              暂无{{ day.label }}模板
            </p>
          </article>
        </div>
      </section>

      <section v-else-if="activeSection === 'logs'" class="content-section">
        <div class="section-heading">
          <div>
            <span>所有汇总都能回到原始记录</span>
            <h2>实际用餐记录</h2>
          </div>
          <button class="primary" @click="openCreate('log')">
            <i class="ri-add-line"></i>记录一餐
          </button>
        </div>
        <div class="filters">
          <button :class="{ active: logDayFilter === 'ALL' }" @click="logDayFilter = 'ALL'">
            全部</button
          ><button
            v-for="day in dayTypes"
            :key="day.value"
            :class="{ active: logDayFilter === day.value }"
            @click="logDayFilter = day.value"
          >
            {{ day.label }}
          </button>
        </div>
        <div v-if="!workspace.mealLogs.length" class="empty-state">
          <i class="ri-bowl-line"></i>
          <h3>还没有真实用餐记录</h3>
          <p>模板不会被算作完成；吃完后再记录食物、耗时和感受。</p>
          <button class="primary" @click="openCreate('log')">记录第一餐</button>
        </div>
        <div v-else class="log-list">
          <article v-for="item in filteredLogs" :key="item.id" class="log-card">
            <time
              >{{ item.date.slice(5).replace('-', '/')
              }}<small>{{ dayLabel(item.dayType) }}</small></time
            >
            <div>
              <div class="tags">
                <span>{{ mealLabel(item.mealType) }} · {{ timeLabel(item.eatenAt) }}</span
                ><span>准备 {{ item.prepMinutes }} 分钟</span
                ><span :class="item.comfort.toLowerCase()">{{ comfortLabel(item.comfort) }}</span>
              </div>
              <h3>{{ joinFoods(item.foods) }}</h3>
              <p>{{ item.observation || '本餐没有补充观察' }}</p>
            </div>
            <div class="quality">
              <strong>{{ item.qualityScore }}</strong
              ><small>/ 5</small>
            </div>
            <div class="actions">
              <button title="编辑" @click="editLog(item)"><i class="ri-edit-line"></i></button
              ><button title="删除" @click="removeLog(item)">
                <i class="ri-delete-bin-line"></i>
              </button>
            </div>
          </article>
        </div>
      </section>

      <section v-else-if="activeSection === 'observations'" class="content-section">
        <div class="section-heading">
          <div>
            <span>来自本人输入与实际记录</span>
            <h2>我的饮食观察</h2>
          </div>
        </div>
        <div class="observation-layout">
          <article class="profile-card">
            <label
              ><span>口味与日常偏好</span
              ><textarea
                v-model="workspace.profile.preferences"
                maxlength="2000"
                placeholder="例如：喜欢清淡、早餐更习惯温热食物"
              ></textarea>
            </label>
            <label
              ><span>我主动避开的食物</span
              ><textarea
                v-model="workspace.profile.avoidances"
                maxlength="2000"
                placeholder="只记录本人已知选择，不让系统推断禁忌"
              ></textarea>
            </label>
            <label
              ><span>本人已有的餐后观察</span
              ><textarea
                v-model="workspace.profile.stomachObservations"
                maxlength="3000"
                placeholder="记录时间、分量和当时感受，避免直接下诊断"
              ></textarea>
            </label>
            <label
              ><span>质量与效率目标</span
              ><textarea
                v-model="workspace.profile.efficiencyGoal"
                maxlength="2000"
                placeholder="例如：工作日早餐 10 分钟内准备好，午餐前一天完成预处理"
              ></textarea>
            </label>
          </article>
          <article class="facts-card">
            <header><strong>食物 × 餐后感受</strong><span>按实际记录自动汇总</span></header>
            <div v-if="!foodObservations.length" class="mini-empty">
              至少记录一餐后，这里才会出现真实观察。
            </div>
            <div v-for="fact in foodObservations" :key="fact.name" class="food-fact">
              <strong>{{ fact.name }}</strong
              ><span>共 {{ fact.total }} 次</span
              ><em>舒服 {{ fact.comfortable }} · 不舒服 {{ fact.uncomfortable }}</em>
            </div>
            <p class="fact-boundary">
              次数只是你的历史记录，不代表医学上的“适合体质”；吃法、分量、时间和当日状态都可能影响感受。
            </p>
          </article>
        </div>
      </section>

      <section v-else class="content-section">
        <div class="section-heading">
          <div>
            <span>把临时决定挪到用餐之前</span>
            <h2>采购与备餐</h2>
          </div>
          <button class="primary" @click="openCreate('task')">
            <i class="ri-add-line"></i>新增事项
          </button>
        </div>
        <div v-if="overdueTasks.length" class="due-banner">
          <i class="ri-alarm-warning-line"></i>{{ overdueTasks.length }} 项已逾期 ·
          仅在本页提醒，没有冒充外部推送
        </div>
        <div v-if="!workspace.preparationTasks.length" class="empty-state">
          <i class="ri-shopping-basket-2-line"></i>
          <h3>还没有采购或备餐事项</h3>
          <p>把买菜、解冻、清洗和分装写成到期事项，减少临时决策。</p>
          <button class="primary" @click="openCreate('task')">新增第一项</button>
        </div>
        <div v-else class="task-list">
          <article
            v-for="item in workspace.preparationTasks"
            :key="item.id"
            :class="{
              done: item.status === 'DONE',
              overdue: item.status === 'OPEN' && item.dueDate < today,
            }"
          >
            <button class="check" @click="toggleTask(item)">
              <i
                :class="
                  item.status === 'DONE'
                    ? 'ri-checkbox-circle-fill'
                    : 'ri-checkbox-blank-circle-line'
                "
              ></i>
            </button>
            <div>
              <div class="tags">
                <span>{{ item.type === 'GROCERY' ? '采购' : '预处理' }}</span
                ><span>{{ formatDate(item.dueDate) }}</span>
              </div>
              <h3>{{ item.title }}</h3>
              <p>{{ item.note || '没有补充说明' }}</p>
            </div>
            <div class="actions">
              <button title="编辑" @click="editTask(item)"><i class="ri-edit-line"></i></button
              ><button title="删除" @click="removeTask(item)">
                <i class="ri-delete-bin-line"></i>
              </button>
            </div>
          </article>
        </div>
      </section>
    </template>

    <div v-if="editorKind" class="modal-backdrop" @click.self="closeEditor()">
      <form class="editor-modal" @submit.prevent="submitEditor">
        <header>
          <div>
            <span>记录真实选择</span>
            <h2>{{ modalTitle }}</h2>
          </div>
          <button type="button" aria-label="关闭" @click="closeEditor()">
            <i class="ri-close-line"></i>
          </button>
        </header>
        <div v-if="editorKind === 'template'" class="form-grid">
          <label
            ><span>日期类型</span
            ><select v-model="templateForm.dayType">
              <option v-for="day in dayTypes" :key="day.value" :value="day.value">
                {{ day.label }}
              </option>
            </select></label
          >
          <label
            ><span>餐次</span
            ><select v-model="templateForm.mealType">
              <option v-for="meal in meals" :key="meal.value" :value="meal.value">
                {{ meal.label }}
              </option>
            </select></label
          >
          <label class="wide"
            ><span>模板名称 *</span
            ><input v-model="templateForm.title" maxlength="120" placeholder="如：工作日快手早餐"
          /></label>
          <label
            ><span>目标时间</span><input v-model="templateForm.targetTime" type="time" required
          /></label>
          <label
            ><span>准备时长（分钟）</span
            ><input
              v-model.number="templateForm.prepMinutes"
              type="number"
              min="0"
              max="1440"
              required
          /></label>
          <label class="wide"
            ><span>常用食物 *</span
            ><input v-model="templateForm.foods" placeholder="燕麦、鸡蛋、苹果；用逗号分隔"
          /></label>
          <label class="wide"
            ><span>执行提示</span
            ><textarea
              v-model="templateForm.note"
              maxlength="2000"
              placeholder="可以记录前一晚需要做的准备"
            ></textarea>
          </label>
          <label class="switch-row wide"
            ><input
              v-model="templateForm.enabled"
              type="checkbox"
            />用于对应日期的今日三餐建议</label
          >
        </div>
        <div v-else-if="editorKind === 'log'" class="form-grid">
          <label
            ><span>实际日期</span><input v-model="logForm.date" type="date" :max="today" required
          /></label>
          <label
            ><span>日期类型</span
            ><select v-model="logForm.dayType">
              <option v-for="day in dayTypes" :key="day.value" :value="day.value">
                {{ day.label }}
              </option>
            </select></label
          >
          <label
            ><span>餐次</span
            ><select v-model="logForm.mealType">
              <option v-for="meal in meals" :key="meal.value" :value="meal.value">
                {{ meal.label }}
              </option>
            </select></label
          >
          <label
            ><span>用餐时间</span><input v-model="logForm.eatenAt" type="time" required
          /></label>
          <label class="wide"
            ><span>实际吃过的食物 *</span
            ><input v-model="logForm.foods" placeholder="米饭、青菜、鸡胸肉；用逗号分隔"
          /></label>
          <label
            ><span>准备时长（分钟）</span
            ><input v-model.number="logForm.prepMinutes" type="number" min="0" max="1440" required
          /></label>
          <label
            ><span>主观质量（1～5）</span
            ><input v-model.number="logForm.qualityScore" type="number" min="1" max="5" required
          /></label>
          <fieldset class="wide">
            <legend>餐后感受</legend>
            <label v-for="option in comforts" :key="option.value"
              ><input v-model="logForm.comfort" type="radio" :value="option.value" /><i
                :class="option.icon"
              ></i
              >{{ option.label }}</label
            >
          </fieldset>
          <label class="wide"
            ><span>当时的真实观察</span
            ><textarea
              v-model="logForm.observation"
              maxlength="2000"
              placeholder="分量、速度、餐后感受或当日状态；只记事实，不下诊断"
            ></textarea>
          </label>
        </div>
        <div v-else class="form-grid">
          <label
            ><span>事项类型</span
            ><select v-model="taskForm.type">
              <option value="GROCERY">采购</option>
              <option value="PREP">预处理 / 备餐</option>
            </select></label
          >
          <label
            ><span>到期日期</span><input v-model="taskForm.dueDate" type="date" required
          /></label>
          <label class="wide"
            ><span>事项 *</span
            ><input
              v-model="taskForm.title"
              maxlength="160"
              placeholder="如：周日晚分装三份早餐食材"
          /></label>
          <label class="wide"
            ><span>备注</span
            ><textarea
              v-model="taskForm.note"
              maxlength="2000"
              placeholder="记录数量、存放位置或需要提前解冻的时间"
            ></textarea>
          </label>
        </div>
        <footer>
          <span>关闭后表单草稿仍保留在本机</span>
          <div>
            <button type="button" @click="closeEditor()">稍后继续</button
            ><button class="primary" type="submit" :disabled="saveState === 'saving'">
              保存并同步
            </button>
          </div>
        </footer>
      </form>
    </div>
  </main>
</template>

<style scoped>
.food-page {
  min-height: 100%;
  padding: 28px clamp(16px, 3vw, 42px) 56px;
  color: #25322b;
  background: #f7f4ed;
}
.hero {
  display: grid;
  grid-template-columns: 1fr 250px;
  gap: 24px;
  padding: clamp(24px, 4vw, 48px);
  border-radius: 28px;
  color: #fdf9eb;
  background:
    radial-gradient(circle at 85% 15%, #66754c 0, transparent 30%),
    linear-gradient(135deg, #203e32, #315b43 62%, #7a6335);
  box-shadow: 0 22px 50px #233b2a24;
}
.eyebrow,
.section-heading span {
  color: #aebd8d;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}
.hero h1 {
  margin: 8px 0 10px;
  font-family: Georgia, 'Noto Serif SC', serif;
  font-size: clamp(34px, 5vw, 56px);
  line-height: 1;
}
.hero p {
  max-width: 720px;
  margin: 0 0 18px;
  color: #d9dfd2;
  line-height: 1.8;
}
.hero textarea {
  width: 100%;
  min-height: 68px;
  padding: 12px 14px;
  resize: vertical;
  border: 1px solid #ffffff2e;
  border-radius: 14px;
  color: #fff;
  background: #ffffff0d;
  font: inherit;
  line-height: 1.6;
}
.hero aside {
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  padding: 22px;
  border: 1px solid #ffffff26;
  border-radius: 22px;
  text-align: center;
  background: #ffffff0d;
  backdrop-filter: blur(10px);
}
.hero aside i {
  color: #d9ca84;
  font-size: 40px;
}
.hero aside strong {
  margin-top: 8px;
  font-family: Georgia, 'Noto Serif SC', serif;
  font-size: 25px;
}
.hero aside span {
  margin: 6px 0 12px;
  color: #d9ca84;
  font-size: 12px;
}
.hero aside small {
  color: #d9dfd2;
  line-height: 1.6;
}
.save-strip {
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 44px;
  margin: 14px 0;
  padding: 8px 14px;
  border: 1px solid #dfe5d7;
  border-radius: 13px;
  color: #687167;
  background: #fff;
  font-size: 13px;
}
.save-strip.error {
  color: #a44f35;
  border-color: #e6c4b8;
  background: #fff8f5;
}
.save-strip.saved {
  color: #316842;
}
.save-strip button {
  margin-left: auto;
  padding: 6px 11px;
  border: 0;
  border-radius: 8px;
  color: #fff;
  background: #a44f35;
  cursor: pointer;
}
.state-card {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 10px;
  min-height: 240px;
  border: 1px solid #e1dfd6;
  border-radius: 20px;
  background: #fff;
}
.state-card.error {
  flex-direction: column;
  color: #a44f35;
}
.state-card button {
  padding: 8px 14px;
  border: 0;
  border-radius: 9px;
  color: #fff;
  background: #315b43;
}
.metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin: 18px 0;
}
.metrics article {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 2px 12px;
  padding: 18px;
  border: 1px solid #e2dfd4;
  border-radius: 18px;
  background: #fff;
}
.metrics span {
  color: #7d8177;
  font-size: 12px;
}
.metrics strong {
  grid-row: span 2;
  color: #315b43;
  font-family: Georgia, serif;
  font-size: 28px;
}
.metrics small {
  color: #a3a398;
}
.section-tabs {
  display: flex;
  gap: 6px;
  margin-bottom: 14px;
  padding: 5px;
  overflow-x: auto;
  border: 1px solid #e2dfd4;
  border-radius: 16px;
  background: #fff;
  scrollbar-width: thin;
}
.section-tabs button {
  flex: 0 0 auto;
  padding: 10px 15px;
  border: 0;
  border-radius: 11px;
  color: #767c73;
  background: transparent;
  cursor: pointer;
}
.section-tabs button.active {
  color: #fff;
  background: #315b43;
}
.section-tabs i {
  margin-right: 7px;
}
.content-section {
  min-height: 420px;
  padding: clamp(18px, 3vw, 30px);
  border: 1px solid #e2dfd4;
  border-radius: 24px;
  background: #fff;
}
.section-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 15px;
  margin-bottom: 22px;
}
.section-heading h2 {
  margin: 4px 0 0;
  font-family: Georgia, 'Noto Serif SC', serif;
  font-size: clamp(22px, 3vw, 30px);
}
.primary {
  padding: 10px 16px;
  border: 0;
  border-radius: 11px;
  color: #fff;
  background: #315b43;
  cursor: pointer;
}
.primary i {
  margin-right: 5px;
}
.meal-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 15px;
}
.meal-card {
  display: flex;
  flex-direction: column;
  min-height: 230px;
  padding: 20px;
  border: 1px solid #e2dfd4;
  border-radius: 19px;
  background: #fbfaf6;
}
.meal-card.complete {
  border-color: #b9cfb5;
  background: #f6faf2;
}
.meal-card header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.meal-card header i {
  color: #a2763e;
  font-size: 22px;
}
.meal-card header span {
  margin-left: auto;
  color: #7d8177;
  font-size: 11px;
}
.meal-card h3 {
  margin: 30px 0 8px;
  font-size: 18px;
}
.meal-card p {
  color: #666d65;
  line-height: 1.6;
}
.meal-card small {
  color: #97998e;
  line-height: 1.5;
}
.text-button {
  align-self: flex-start;
  margin-top: auto;
  padding: 8px 0 0;
  border: 0;
  color: #315b43;
  background: transparent;
  font-weight: 700;
  cursor: pointer;
}
.boundary-card {
  display: flex;
  gap: 12px;
  margin-top: 18px;
  padding: 17px;
  border-radius: 15px;
  color: #655b43;
  background: #faf3df;
}
.boundary-card i {
  color: #9c7b35;
  font-size: 24px;
}
.boundary-card p {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.6;
}
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 300px;
  color: #777d74;
  text-align: center;
}
.empty-state > i {
  color: #aebd8d;
  font-size: 48px;
}
.empty-state h3 {
  margin: 12px 0 2px;
  color: #344039;
}
.empty-state p {
  margin: 0 0 18px;
}
.day-columns {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 18px;
}
.day-columns > article {
  padding: 16px;
  border-radius: 18px;
  background: #f8f7f2;
}
.day-columns > article > header {
  margin-bottom: 12px;
}
.day-columns > article > header div {
  display: flex;
  justify-content: space-between;
}
.day-columns > article > header span {
  color: #8a8d83;
  font-size: 12px;
}
.list-card {
  display: grid;
  grid-template-columns: 38px 1fr auto;
  gap: 11px;
  margin-top: 10px;
  padding: 14px;
  border: 1px solid #e3e1d8;
  border-radius: 14px;
  background: #fff;
}
.list-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  color: #7a6335;
  background: #f3ead4;
}
.list-card h3,
.log-card h3,
.task-list h3 {
  margin: 7px 0 3px;
  font-size: 15px;
}
.list-card p,
.log-card p,
.task-list p {
  margin: 0;
  color: #70766d;
  font-size: 13px;
  line-height: 1.5;
}
.list-card small {
  color: #9a9d93;
}
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}
.tags span {
  padding: 3px 7px;
  border-radius: 999px;
  color: #687167;
  background: #eef1e8;
  font-size: 10px;
}
.actions {
  display: flex;
  gap: 4px;
}
.actions button,
.edit {
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 8px;
  color: #747970;
  background: #f2f2ed;
  cursor: pointer;
}
.column-empty {
  padding: 40px 0;
  color: #999c92;
  text-align: center;
}
.filters {
  display: flex;
  gap: 6px;
  margin: -8px 0 16px;
}
.filters button {
  padding: 7px 12px;
  border: 1px solid #dddcd3;
  border-radius: 999px;
  color: #6f766d;
  background: #fff;
  cursor: pointer;
}
.filters button.active {
  color: #fff;
  border-color: #315b43;
  background: #315b43;
}
.log-list {
  display: grid;
  gap: 9px;
}
.log-card {
  display: grid;
  grid-template-columns: 64px 1fr auto auto;
  gap: 14px;
  align-items: center;
  padding: 15px;
  border: 1px solid #e2dfd4;
  border-radius: 15px;
}
.log-card time {
  color: #315b43;
  font-family: Georgia, serif;
  font-weight: 700;
}
.log-card time small {
  display: block;
  margin-top: 5px;
  color: #92978e;
  font-family: inherit;
  font-weight: 400;
}
.quality {
  display: flex;
  align-items: baseline;
  color: #a2763e;
}
.quality strong {
  font-size: 24px;
}
.quality small {
  color: #9a9d93;
}
.tags .comfortable {
  color: #2e6b42;
  background: #e7f3e9;
}
.tags .uncomfortable {
  color: #a14c37;
  background: #f9e9e4;
}
.observation-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(320px, 0.9fr);
  gap: 18px;
}
.profile-card,
.facts-card {
  padding: 20px;
  border: 1px solid #e2dfd4;
  border-radius: 18px;
  background: #fbfaf6;
}
.profile-card {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.profile-card label {
  display: flex;
  flex-direction: column;
  gap: 7px;
  color: #60675f;
  font-size: 12px;
}
.profile-card textarea {
  min-height: 105px;
  resize: vertical;
}
.facts-card header {
  display: flex;
  justify-content: space-between;
  margin-bottom: 10px;
}
.facts-card header span {
  color: #92968d;
  font-size: 11px;
}
.food-fact {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 4px 10px;
  padding: 11px 0;
  border-bottom: 1px solid #e7e4da;
}
.food-fact span {
  color: #82877e;
  font-size: 11px;
}
.food-fact em {
  grid-column: 1 / -1;
  color: #657067;
  font-size: 12px;
  font-style: normal;
}
.mini-empty {
  padding: 50px 12px;
  color: #92968d;
  text-align: center;
}
.fact-boundary {
  margin: 15px 0 0;
  color: #886f43;
  font-size: 12px;
  line-height: 1.65;
}
.due-banner {
  margin-bottom: 13px;
  padding: 11px 14px;
  border-radius: 11px;
  color: #9a503b;
  background: #fff0eb;
}
.task-list {
  display: grid;
  gap: 8px;
}
.task-list article {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 12px;
  align-items: center;
  padding: 15px;
  border: 1px solid #e2dfd4;
  border-radius: 14px;
}
.task-list article.done {
  opacity: 0.55;
}
.task-list article.done h3 {
  text-decoration: line-through;
}
.task-list article.overdue {
  border-color: #e7b9aa;
}
.check {
  border: 0;
  color: #56805d;
  background: transparent;
  font-size: 23px;
  cursor: pointer;
}
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  display: grid;
  place-items: center;
  padding: 16px;
  background: #17241dc2;
}
.editor-modal {
  width: min(680px, 100%);
  max-height: min(780px, calc(100vh - 32px));
  overflow: auto;
  border-radius: 22px;
  background: #fff;
  box-shadow: 0 30px 80px #0c151055;
}
.editor-modal > header {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 22px 14px;
  background: #fff;
}
.editor-modal > header span {
  color: #9b7c43;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.12em;
}
.editor-modal > header h2 {
  margin: 3px 0 0;
  font-family: Georgia, 'Noto Serif SC', serif;
}
.editor-modal > header button {
  width: 35px;
  height: 35px;
  border: 0;
  border-radius: 10px;
  background: #f1f1ec;
  cursor: pointer;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  padding: 8px 22px 22px;
}
.form-grid > label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: #60675f;
  font-size: 12px;
}
.form-grid .wide,
.form-grid fieldset {
  grid-column: 1 / -1;
}
.form-grid input,
.form-grid select,
.form-grid textarea,
.profile-card textarea {
  width: 100%;
  padding: 10px 11px;
  border: 1px solid #d8d8cf;
  border-radius: 10px;
  color: #303b35;
  background: #fff;
  font: inherit;
  box-sizing: border-box;
}
.form-grid textarea {
  min-height: 80px;
  resize: vertical;
}
.form-grid fieldset {
  display: flex;
  gap: 8px;
  padding: 11px;
  border: 1px solid #d8d8cf;
  border-radius: 11px;
}
.form-grid fieldset legend {
  padding: 0 5px;
  color: #60675f;
  font-size: 12px;
}
.form-grid fieldset label {
  flex: 1;
  padding: 8px;
  border-radius: 9px;
  background: #f4f4ef;
  text-align: center;
  cursor: pointer;
}
.form-grid fieldset input {
  width: auto;
  margin-right: 4px;
}
.switch-row {
  flex-direction: row !important;
  align-items: center;
}
.switch-row input {
  width: auto;
}
.editor-modal footer {
  position: sticky;
  bottom: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 14px 22px;
  border-top: 1px solid #e6e4dc;
  background: #fff;
}
.editor-modal footer > span {
  color: #999b92;
  font-size: 11px;
}
.editor-modal footer div {
  display: flex;
  gap: 8px;
}
.editor-modal footer button {
  padding: 9px 14px;
  border: 0;
  border-radius: 9px;
  cursor: pointer;
}
.spin {
  display: inline-block;
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 900px) {
  .hero {
    grid-template-columns: 1fr;
  }
  .hero aside {
    align-items: flex-start;
    text-align: left;
  }
  .metrics {
    grid-template-columns: 1fr 1fr;
  }
  .meal-grid {
    grid-template-columns: 1fr;
  }
  .day-columns,
  .observation-layout {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 600px) {
  .food-page {
    padding: 12px 10px 72px;
  }
  .hero {
    padding: 22px 18px;
    border-radius: 20px;
  }
  .metrics {
    gap: 7px;
  }
  .metrics article {
    padding: 13px;
  }
  .metrics strong {
    font-size: 22px;
  }
  .content-section {
    padding: 16px 12px;
    border-radius: 18px;
  }
  .section-heading {
    align-items: flex-end;
  }
  .section-heading .primary {
    padding: 9px 11px;
  }
  .log-card {
    grid-template-columns: 50px 1fr auto;
  }
  .log-card .quality {
    display: none;
  }
  .log-card .actions {
    grid-column: 2 / -1;
  }
  .profile-card {
    grid-template-columns: 1fr;
  }
  .form-grid {
    grid-template-columns: 1fr;
    padding-inline: 16px;
  }
  .form-grid .wide,
  .form-grid fieldset {
    grid-column: 1;
  }
  .editor-modal > header,
  .editor-modal footer {
    padding-inline: 16px;
  }
  .editor-modal footer {
    align-items: flex-end;
  }
  .editor-modal footer > span {
    display: none;
  }
}
</style>
