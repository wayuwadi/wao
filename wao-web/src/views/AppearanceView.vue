<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import {
  appearancePhotoUrl,
  fetchAppearanceWorkspace,
  saveAppearanceWorkspace,
  uploadAppearancePhoto,
  type AppearanceGarment,
  type AppearanceNote,
  type AppearanceOutfit,
  type AppearancePlan,
  type AppearancePlanType,
  type AppearanceWearLog,
  type AppearanceWorkspace,
  type GarmentStatus,
} from '@/api/appearance'
import { toast } from '@/composables/toast'

type Section = 'wardrobe' | 'outfits' | 'profile' | 'plans' | 'notes'
type EditorKind = 'garment' | 'outfit' | 'wear' | 'plan' | 'note' | ''
type SaveState = 'idle' | 'saving' | 'saved' | 'error'

const WORKSPACE_DRAFT_KEY = 'wao-appearance-workspace-draft-v1'
const FORM_DRAFT_KEY = 'wao-appearance-form-draft-v1'
const today = isoDate(new Date())
const sections: Array<{ id: Section; label: string; icon: string }> = [
  { id: 'wardrobe', label: '服饰物品库', icon: 'ri-shirt-line' },
  { id: 'outfits', label: '穿搭与日历', icon: 'ri-layout-masonry-line' },
  { id: 'profile', label: '个人档案', icon: 'ri-user-star-line' },
  { id: 'plans', label: '计划与提醒', icon: 'ri-notification-3-line' },
  { id: 'notes', label: '搭配参考', icon: 'ri-palette-line' },
]
const statusOptions: Array<{ value: GarmentStatus; label: string }> = [
  { value: 'WARDROBE', label: '在衣柜' },
  { value: 'CLEANING', label: '待清洗' },
  { value: 'IDLE', label: '闲置' },
  { value: 'DONATED', label: '已捐赠' },
]
const planTypes: Array<{ value: AppearancePlanType; label: string; icon: string }> = [
  { value: 'EVENT', label: '未来场合', icon: 'ri-calendar-event-line' },
  { value: 'CLEANING', label: '衣物清洗', icon: 'ri-water-flash-line' },
  { value: 'STORAGE', label: '换季收纳', icon: 'ri-archive-drawer-line' },
  { value: 'PURCHASE', label: '采购清单', icon: 'ri-shopping-bag-3-line' },
]
const scoreFields = [
  { key: 'hairScore', label: '头部', weight: '20%', icon: 'ri-scissors-cut-line' },
  { key: 'clothingScore', label: '服饰', weight: '60%', icon: 'ri-shirt-line' },
  { key: 'shoesScore', label: '鞋履', weight: '20%', icon: 'ri-footprint-line' },
  { key: 'accessoryScore', label: '配件', weight: '按需', icon: 'ri-watch-line' },
  { key: 'bodyScore', label: '体态', weight: '基础', icon: 'ri-run-line' },
  { key: 'presenceScore', label: '仪态', weight: '长期', icon: 'ri-speak-line' },
] as const

const loading = ref(true)
const loadError = ref('')
const activeSection = ref<Section>('wardrobe')
const workspace = ref<AppearanceWorkspace>(emptyWorkspace())
const initialized = ref(false)
const saveState = ref<SaveState>('idle')
const saveError = ref('')
const filterStatus = ref<GarmentStatus | 'ALL'>('ALL')
const filterCategory = ref('全部')
const filterQuery = ref('')
const editorKind = ref<EditorKind>('')
const editingId = ref('')
const uploading = ref(false)
let saveTimer: ReturnType<typeof setTimeout> | null = null
let applyingServer = false

const garmentForm = reactive({
  name: '',
  category: '上装',
  seasons: '',
  styles: '',
  colors: '',
  occasions: '',
  material: '',
  status: 'WARDROBE' as GarmentStatus,
  imageId: null as string | null,
  purchaseDate: '',
  note: '',
})
const outfitForm = reactive({
  name: '',
  garmentIds: [] as string[],
  scenarios: '',
  imageId: null as string | null,
  note: '',
})
const wearForm = reactive({ date: today, outfitId: '', garmentIds: [] as string[], feeling: '' })
const planForm = reactive({
  type: 'EVENT' as AppearancePlanType,
  title: '',
  dueDate: today,
  scenario: '',
  outfitId: '',
  note: '',
})
const noteForm = reactive({ title: '', content: '', tags: '' })

const wardrobeItems = computed(() =>
  workspace.value.garments.filter((item) => {
    const query = filterQuery.value.trim().toLowerCase()
    return (
      (filterStatus.value === 'ALL' || item.status === filterStatus.value) &&
      (filterCategory.value === '全部' || item.category === filterCategory.value) &&
      (!query ||
        [item.name, item.category, item.material, ...item.colors, ...item.styles, ...item.occasions]
          .join(' ')
          .toLowerCase()
          .includes(query))
    )
  }),
)
const categories = computed(() => [
  '全部',
  ...new Set(workspace.value.garments.map((item) => item.category)),
])
const activeGarments = computed(() =>
  workspace.value.garments.filter((item) => item.status !== 'DONATED'),
)
const lowFrequency = computed(() =>
  workspace.value.garments
    .filter((item) => item.status === 'WARDROBE' && item.wearCount <= 1)
    .sort((a, b) => a.wearCount - b.wearCount),
)
const idleCount = computed(
  () => workspace.value.garments.filter((item) => item.status === 'IDLE').length,
)
const duplicateCandidates = computed(() => {
  const grouped = new Map<string, AppearanceGarment[]>()
  workspace.value.garments
    .filter((item) => item.status !== 'DONATED' && item.colors.length)
    .forEach((item) => {
      const key = `${item.category}|${[...item.colors].sort().join(',')}`
      grouped.set(key, [...(grouped.get(key) ?? []), item])
    })
  return [...grouped.values()].filter((items) => items.length > 1)
})
const openPlans = computed(() =>
  workspace.value.plans
    .filter((item) => item.status === 'OPEN')
    .sort((a, b) => a.dueDate.localeCompare(b.dueDate)),
)
const overduePlans = computed(() => openPlans.value.filter((item) => item.dueDate < today))
const dueTodayPlans = computed(() => openPlans.value.filter((item) => item.dueDate === today))
const overallScore = computed(() => {
  const p = workspace.value.profile
  const weighted = [
    [p.hairScore, 20],
    [p.clothingScore, 60],
    [p.shoesScore, 20],
  ] as Array<[number | null, number]>
  const known = weighted.filter(([score]) => score !== null)
  if (!known.length) return null
  return Math.round(
    known.reduce((sum, [score, weight]) => sum + (score ?? 0) * weight, 0) /
      known.reduce((sum, [, weight]) => sum + weight, 0),
  )
})
const recentWear = computed(() => workspace.value.wearLogs.slice(0, 14))
const repeatedOutfitNames = computed(() => {
  const fromDate = isoDate(new Date(Date.now() - 6 * 86_400_000))
  const counts = new Map<string, number>()
  workspace.value.wearLogs
    .filter((item) => item.date >= fromDate && item.outfitId)
    .forEach((item) => counts.set(item.outfitId!, (counts.get(item.outfitId!) ?? 0) + 1))
  return [...counts.entries()]
    .filter(([, count]) => count >= 3)
    .map(([outfitId, count]) => `${outfitName(outfitId)} · 近 7 天 ${count} 次`)
})
const modalTitle = computed(
  () =>
    ({
      garment: editingId.value ? '编辑物品' : '录入物品',
      outfit: editingId.value ? '编辑穿搭' : '保存穿搭',
      wear: '记录今天穿了什么',
      plan: '新增计划事项',
      note: '记录穿搭笔记',
      '': '',
    })[editorKind.value],
)

function emptyWorkspace(): AppearanceWorkspace {
  return {
    garments: [],
    outfits: [],
    plans: [],
    wearLogs: [],
    notes: [],
    updatedAt: null,
    principles:
      '清爽、干净、舒服；不追求名牌，追求整体风格搭配和谐。多比贵好，合适比喜欢更好。达到 80 分的保持，只有 60 分或 30 分的就大胆改变。',
    profile: {
      heightCm: null,
      weightKg: null,
      shoulderCm: null,
      waistCm: null,
      measuredOn: null,
      skinTone: '',
      faceShape: '',
      hairTexture: '',
      stylePreferences: '',
      avoidances: '',
      postureGoal: '',
      hairScore: null,
      clothingScore: null,
      shoesScore: null,
      accessoryScore: null,
      bodyScore: null,
      presenceScore: null,
    },
  }
}
function isoDate(date: Date) {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(date)
}
function id(prefix: string) {
  return `${prefix}${crypto.randomUUID().replace(/-/g, '').slice(0, 24)}`
}
function splitTags(value: string) {
  return [
    ...new Set(
      value
        .split(/[,，、]/)
        .map((item) => item.trim())
        .filter(Boolean),
    ),
  ].slice(0, 30)
}
function joinTags(value: string[] | null | undefined) {
  return (value ?? []).join('、')
}
function statusLabel(value: GarmentStatus) {
  return statusOptions.find((item) => item.value === value)?.label ?? value
}
function outfitName(id: string | null) {
  return workspace.value.outfits.find((item) => item.id === id)?.name ?? ''
}
function garmentName(id: string) {
  return workspace.value.garments.find((item) => item.id === id)?.name ?? '已移除物品'
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
    /* 内存继续保留。 */
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
    const server = await fetchAppearanceWorkspace()
    const draft = readJson<AppearanceWorkspace>(WORKSPACE_DRAFT_KEY)
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
    const draft = readJson<AppearanceWorkspace>(WORKSPACE_DRAFT_KEY)
    if (draft) {
      applyingServer = true
      workspace.value = draft
      await nextTick()
      applyingServer = false
      initialized.value = true
      saveState.value = 'error'
      saveError.value = '服务端暂时不可用，当前显示本机草稿。'
    } else loadError.value = errorMessage(error, '形象管理加载失败')
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
  const payload = savePayload(workspace.value)
  const fingerprint = JSON.stringify(payload)
  saveState.value = 'saving'
  saveError.value = ''
  try {
    const saved = await saveAppearanceWorkspace(payload)
    if (JSON.stringify(savePayload(workspace.value)) === fingerprint) {
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
function savePayload(value: AppearanceWorkspace) {
  const payload = JSON.parse(JSON.stringify(value)) as AppearanceWorkspace
  const measures = ['heightCm', 'weightKg', 'shoulderCm', 'waistCm'] as const
  measures.forEach((key) => {
    if (typeof payload.profile[key] !== 'number') payload.profile[key] = null
  })
  if (!payload.profile.measuredOn) payload.profile.measuredOn = null
  return payload
}

function resetForms() {
  Object.assign(garmentForm, {
    name: '',
    category: '上装',
    seasons: '',
    styles: '',
    colors: '',
    occasions: '',
    material: '',
    status: 'WARDROBE',
    imageId: null,
    purchaseDate: '',
    note: '',
  })
  Object.assign(outfitForm, { name: '', garmentIds: [], scenarios: '', imageId: null, note: '' })
  Object.assign(wearForm, { date: today, outfitId: '', garmentIds: [], feeling: '' })
  Object.assign(planForm, {
    type: 'EVENT',
    title: '',
    dueDate: today,
    scenario: '',
    outfitId: '',
    note: '',
  })
  Object.assign(noteForm, { title: '', content: '', tags: '' })
}
function editorForm() {
  if (editorKind.value === 'garment') return garmentForm
  if (editorKind.value === 'outfit') return outfitForm
  if (editorKind.value === 'wear') return wearForm
  if (editorKind.value === 'plan') return planForm
  if (editorKind.value === 'note') return noteForm
  return null
}
function openCreate(kind: Exclude<EditorKind, ''>) {
  resetForms()
  editingId.value = ''
  editorKind.value = kind
  const draft = readJson<{ kind: EditorKind; editingId?: string; form: Record<string, unknown> }>(
    FORM_DRAFT_KEY,
  )
  if (draft?.kind === kind && !draft.editingId) Object.assign(editorForm()!, draft.form)
}
function editGarment(item: AppearanceGarment) {
  resetForms()
  editorKind.value = 'garment'
  editingId.value = item.id
  Object.assign(garmentForm, {
    ...item,
    seasons: joinTags(item.seasons),
    styles: joinTags(item.styles),
    colors: joinTags(item.colors),
    occasions: joinTags(item.occasions),
    purchaseDate: item.purchaseDate ?? '',
  })
  const draft = readJson<{ kind: EditorKind; editingId?: string; form: Record<string, unknown> }>(
    FORM_DRAFT_KEY,
  )
  if (draft?.kind === 'garment' && draft.editingId === item.id)
    Object.assign(garmentForm, draft.form)
}
function editOutfit(item: AppearanceOutfit) {
  resetForms()
  editorKind.value = 'outfit'
  editingId.value = item.id
  Object.assign(outfitForm, {
    ...item,
    garmentIds: [...item.garmentIds],
    scenarios: joinTags(item.scenarios),
  })
  const draft = readJson<{ kind: EditorKind; editingId?: string; form: Record<string, unknown> }>(
    FORM_DRAFT_KEY,
  )
  if (draft?.kind === 'outfit' && draft.editingId === item.id) Object.assign(outfitForm, draft.form)
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
function requireForm(value: string, message: string) {
  if (!value.trim()) {
    toast(message, 'ri-information-line')
    return false
  }
  return true
}
function submitEditor() {
  if (editorKind.value === 'garment') {
    if (
      !requireForm(garmentForm.name, '请填写物品名称') ||
      !requireForm(garmentForm.category, '请填写品类')
    )
      return
    const value: AppearanceGarment = {
      id: editingId.value || id('ag'),
      name: garmentForm.name.trim(),
      category: garmentForm.category.trim(),
      seasons: splitTags(garmentForm.seasons),
      styles: splitTags(garmentForm.styles),
      colors: splitTags(garmentForm.colors),
      occasions: splitTags(garmentForm.occasions),
      material: garmentForm.material.trim(),
      status: garmentForm.status,
      imageId: garmentForm.imageId,
      purchaseDate: garmentForm.purchaseDate || null,
      lastWornDate:
        workspace.value.garments.find((item) => item.id === editingId.value)?.lastWornDate ?? null,
      wearCount:
        workspace.value.garments.find((item) => item.id === editingId.value)?.wearCount ?? 0,
      note: garmentForm.note.trim(),
    }
    upsert(workspace.value.garments, value)
  } else if (editorKind.value === 'outfit') {
    if (!requireForm(outfitForm.name, '请填写穿搭名称') || !outfitForm.garmentIds.length) {
      if (!outfitForm.garmentIds.length) toast('请至少选择一件物品', 'ri-information-line')
      return
    }
    upsert(workspace.value.outfits, {
      id: editingId.value || id('ao'),
      name: outfitForm.name.trim(),
      garmentIds: [...outfitForm.garmentIds],
      scenarios: splitTags(outfitForm.scenarios),
      imageId: outfitForm.imageId,
      note: outfitForm.note.trim(),
    })
  } else if (editorKind.value === 'wear') {
    if (!wearForm.date || (!wearForm.outfitId && !wearForm.garmentIds.length)) {
      toast('请选择穿着日期和组合或物品', 'ri-information-line')
      return
    }
    workspace.value.wearLogs.unshift({
      id: id('aw'),
      date: wearForm.date,
      outfitId: wearForm.outfitId || null,
      garmentIds: [...wearForm.garmentIds],
      feeling: wearForm.feeling.trim(),
    })
  } else if (editorKind.value === 'plan') {
    if (!requireForm(planForm.title, '请填写计划事项') || !planForm.dueDate) return
    workspace.value.plans.push({
      id: id('apn'),
      type: planForm.type,
      title: planForm.title.trim(),
      dueDate: planForm.dueDate,
      scenario: planForm.scenario.trim(),
      outfitId: planForm.outfitId || null,
      status: 'OPEN',
      note: planForm.note.trim(),
    })
  } else if (editorKind.value === 'note') {
    if (
      !requireForm(noteForm.title, '请填写笔记标题') ||
      !requireForm(noteForm.content, '请记录真实穿着感受')
    )
      return
    workspace.value.notes.unshift({
      id: id('an'),
      title: noteForm.title.trim(),
      content: noteForm.content.trim(),
      tags: splitTags(noteForm.tags),
      createdAt: new Date().toISOString(),
    })
  }
  queueSave()
  closeEditor(true)
  toast('已加入形象档案，正在同步', 'ri-check-line')
}
function upsert<T extends { id: string }>(items: T[], value: T) {
  const index = items.findIndex((item) => item.id === value.id)
  if (index >= 0) items.splice(index, 1, value)
  else items.push(value)
}
async function uploadPhoto(event: Event, target: 'garment' | 'outfit') {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || uploading.value) return
  uploading.value = true
  try {
    const photo = await uploadAppearancePhoto(file)
    if (target === 'garment') garmentForm.imageId = photo.id
    else outfitForm.imageId = photo.id
    toast('实拍图已私密保存', 'ri-image-add-line')
  } catch (error) {
    toast(errorMessage(error, '实拍图上传失败'), 'ri-error-warning-line')
  } finally {
    uploading.value = false
  }
}
function removeGarment(item: AppearanceGarment) {
  const used =
    workspace.value.outfits.some((outfit) => outfit.garmentIds.includes(item.id)) ||
    workspace.value.wearLogs.some((log) => log.garmentIds.includes(item.id))
  if (used) {
    toast('这件物品仍被穿搭或穿着记录引用，请先调整引用', 'ri-links-line')
    return
  }
  if (!window.confirm(`确认移除“${item.name}”？`)) return
  workspace.value.garments = workspace.value.garments.filter((value) => value.id !== item.id)
  queueSave()
}
function removeOutfit(item: AppearanceOutfit) {
  const used =
    workspace.value.wearLogs.some((log) => log.outfitId === item.id) ||
    workspace.value.plans.some((plan) => plan.outfitId === item.id)
  if (used) {
    toast('这套穿搭仍被日历或计划引用，请先调整引用', 'ri-links-line')
    return
  }
  if (!window.confirm(`确认移除“${item.name}”？`)) return
  workspace.value.outfits = workspace.value.outfits.filter((value) => value.id !== item.id)
  queueSave()
}
function togglePlan(item: AppearancePlan) {
  item.status = item.status === 'OPEN' ? 'DONE' : 'OPEN'
  queueSave()
}
function removeWear(item: AppearanceWearLog) {
  workspace.value.wearLogs = workspace.value.wearLogs.filter((value) => value.id !== item.id)
  queueSave()
}
function removeNote(item: AppearanceNote) {
  if (!window.confirm(`确认删除笔记“${item.title}”？`)) return
  workspace.value.notes = workspace.value.notes.filter((value) => value.id !== item.id)
  queueSave()
}
function planState(item: AppearancePlan) {
  if (item.status === 'DONE') return '已完成'
  if (item.dueDate < today) return '已逾期'
  if (item.dueDate === today) return '今天到期'
  return '待安排'
}
function setScore(key: (typeof scoreFields)[number]['key'], value: number) {
  workspace.value.profile[key] = value
  queueSave()
}
function scoreAdvice(score: number | null) {
  if (score === null) return '待评估'
  if (score >= 80) return '保持现状'
  return '值得大胆改变'
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
  [garmentForm, outfitForm, wearForm, planForm, noteForm],
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
onMounted(load)
onBeforeUnmount(() => {
  if (saveTimer) {
    clearTimeout(saveTimer)
    void persist()
  }
})
</script>

<template>
  <main class="appearance-page">
    <header class="hero">
      <div class="hero-copy">
        <span class="eyebrow">PERSONAL STYLE SYSTEM</span>
        <h1>形象管理</h1>
        <p>提前把选择做好，让清爽、舒服、合时宜成为稳定标准，而不是临到出门再按旧惯性决定。</p>
        <textarea
          v-model="workspace.principles"
          maxlength="2000"
          aria-label="我的形象理念"
          :disabled="loading"
        ></textarea>
      </div>
      <div class="hero-score">
        <span>整体结构分</span>
        <strong>{{ overallScore ?? '—' }}</strong>
        <small>{{
          overallScore === null
            ? '完成头部、服饰、鞋履自评后生成'
            : overallScore >= 80
              ? '保持已经有效的标准'
              : '低分项就是下一次改变的方向'
        }}</small>
      </div>
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
      <span v-if="saveState === 'saving'">正在保存到个人档案…</span>
      <span v-else-if="saveState === 'error'">{{ saveError }}</span>
      <span v-else-if="saveState === 'saved'">已保存，刷新或换设备仍可读取</span>
      <span v-else>所有记录仅当前成员可见</span>
      <button v-if="saveState === 'error'" type="button" @click="persist">重试保存</button>
    </div>

    <section v-if="loading" class="state-card">
      <i class="ri-loader-4-line spin"></i>正在读取衣橱和档案…
    </section>
    <section v-else-if="loadError" class="state-card error">
      <i class="ri-error-warning-line"></i><strong>形象管理加载失败</strong
      ><span>{{ loadError }}</span
      ><button @click="load">重试</button>
    </section>

    <template v-else>
      <section class="overview-grid">
        <article>
          <i class="ri-shirt-line"></i>
          <div>
            <strong>{{ workspace.garments.length }}</strong
            ><span>件物品</span>
          </div>
        </article>
        <article>
          <i class="ri-layout-masonry-line"></i>
          <div>
            <strong>{{ workspace.outfits.length }}</strong
            ><span>套穿搭</span>
          </div>
        </article>
        <article :class="{ warning: lowFrequency.length }">
          <i class="ri-time-line"></i>
          <div>
            <strong>{{ lowFrequency.length }}</strong
            ><span>件低频 · {{ idleCount }} 件闲置</span>
          </div>
        </article>
        <article :class="{ warning: overduePlans.length }">
          <i class="ri-alarm-warning-line"></i>
          <div>
            <strong>{{ overduePlans.length }}</strong
            ><span>项逾期</span>
          </div>
        </article>
      </section>

      <nav class="section-tabs" aria-label="形象管理分区">
        <button
          v-for="section in sections"
          :key="section.id"
          :class="{ active: activeSection === section.id }"
          @click="activeSection = section.id"
        >
          <i :class="section.icon"></i>{{ section.label }}
        </button>
      </nav>

      <section v-if="activeSection === 'wardrobe'" class="content-section">
        <div class="section-head">
          <div>
            <span>衣橱全貌</span>
            <h2>服饰物品库</h2>
          </div>
          <button class="primary" @click="openCreate('garment')">
            <i class="ri-add-line"></i>录入物品
          </button>
        </div>
        <div class="filters">
          <input
            v-model="filterQuery"
            placeholder="搜索名称、颜色、风格或场合"
            aria-label="搜索物品"
          />
          <select v-model="filterCategory" aria-label="筛选品类">
            <option v-for="item in categories" :key="item">{{ item }}</option>
          </select>
          <select v-model="filterStatus" aria-label="筛选状态">
            <option value="ALL">全部状态</option>
            <option v-for="item in statusOptions" :key="item.value" :value="item.value">
              {{ item.label }}
            </option>
          </select>
        </div>
        <div v-if="!workspace.garments.length" class="empty-state">
          <i class="ri-shirt-line"></i>
          <h3>衣橱还是空的</h3>
          <p>先录入下一次真正会穿的一件衣服或鞋子，不需要一次整理完。</p>
          <button @click="openCreate('garment')">录入第一件</button>
        </div>
        <div v-else-if="!wardrobeItems.length" class="empty-state compact">
          <i class="ri-search-line"></i>
          <p>没有符合当前筛选的物品</p>
        </div>
        <div v-else class="garment-grid">
          <article v-for="item in wardrobeItems" :key="item.id" class="garment-card">
            <div class="photo">
              <img
                v-if="item.imageId"
                :src="appearancePhotoUrl(item.imageId)"
                :alt="`${item.name}实拍`"
              /><i v-else class="ri-shirt-line"></i><span>{{ statusLabel(item.status) }}</span>
            </div>
            <div class="card-body">
              <div class="card-title">
                <div>
                  <small>{{ item.category }}</small>
                  <h3>{{ item.name }}</h3>
                </div>
                <div class="card-actions">
                  <button aria-label="编辑物品" @click="editGarment(item)">
                    <i class="ri-edit-line"></i></button
                  ><button aria-label="删除物品" @click="removeGarment(item)">
                    <i class="ri-delete-bin-line"></i>
                  </button>
                </div>
              </div>
              <div class="chips">
                <span
                  v-for="tag in [...item.colors, ...item.styles, ...item.seasons].slice(0, 5)"
                  :key="tag"
                  >{{ tag }}</span
                >
              </div>
              <div class="wear-stat">
                <strong>{{ item.wearCount }}</strong> 次穿着<small>{{
                  item.lastWornDate ? `最近 ${item.lastWornDate}` : '尚无穿着记录'
                }}</small>
              </div>
              <p v-if="item.note">{{ item.note }}</p>
            </div>
          </article>
        </div>
      </section>

      <section v-else-if="activeSection === 'outfits'" class="content-section">
        <div class="section-head">
          <div>
            <span>组合与真实记录</span>
            <h2>穿搭组合管理</h2>
          </div>
          <div class="head-actions">
            <button @click="openCreate('wear')">
              <i class="ri-calendar-check-line"></i>记录穿着</button
            ><button
              class="primary"
              :disabled="!activeGarments.length"
              @click="openCreate('outfit')"
            >
              <i class="ri-add-line"></i>保存组合
            </button>
          </div>
        </div>
        <div class="split-layout">
          <div>
            <div v-if="!workspace.outfits.length" class="empty-state compact">
              <i class="ri-layout-masonry-line"></i>
              <h3>还没有固定穿搭</h3>
              <p>
                {{
                  activeGarments.length
                    ? '把已经协调舒服的一套保存下来，下次直接选。'
                    : '先录入物品，再把它们组合成穿搭。'
                }}
              </p>
            </div>
            <div class="outfit-grid">
              <article v-for="item in workspace.outfits" :key="item.id" class="outfit-card">
                <div class="outfit-photo">
                  <img
                    v-if="item.imageId"
                    :src="appearancePhotoUrl(item.imageId)"
                    :alt="`${item.name}上身实拍`"
                  /><i v-else class="ri-user-smile-line"></i>
                </div>
                <div>
                  <small>{{ item.scenarios.join(' · ') || '日常' }}</small>
                  <h3>{{ item.name }}</h3>
                  <p>{{ item.garmentIds.map(garmentName).join(' + ') }}</p>
                  <blockquote v-if="item.note">{{ item.note }}</blockquote>
                  <div class="card-actions text">
                    <button @click="editOutfit(item)">编辑</button
                    ><button @click="removeOutfit(item)">移除</button>
                  </div>
                </div>
              </article>
            </div>
          </div>
          <aside class="wear-calendar">
            <div class="aside-head">
              <div>
                <span>最近 14 次</span>
                <h3>穿搭日历</h3>
              </div>
              <button @click="openCreate('wear')"><i class="ri-add-line"></i></button>
            </div>
            <div v-if="!recentWear.length" class="mini-empty">
              记录实际穿着后，会在这里看到近期频率和感受。
            </div>
            <article v-for="item in recentWear" :key="item.id">
              <time>{{ formatDate(item.date) }}</time>
              <div>
                <strong>{{
                  outfitName(item.outfitId) || item.garmentIds.map(garmentName).join(' + ')
                }}</strong>
                <p>{{ item.feeling || '未记录穿着感受' }}</p>
              </div>
              <button aria-label="删除穿着记录" @click="removeWear(item)">
                <i class="ri-close-line"></i>
              </button>
            </article>
          </aside>
        </div>
        <div
          v-if="lowFrequency.length || duplicateCandidates.length || repeatedOutfitNames.length"
          class="review-card"
        >
          <div>
            <span>衣橱复盘</span>
            <h3>用真实记录检查低频、重复候选和短期高频</h3>
          </div>
          <div class="chips">
            <span v-for="item in lowFrequency.slice(0, 12)" :key="item.id"
              >{{ item.name }} · {{ item.wearCount }} 次</span
            >
          </div>
          <div v-if="duplicateCandidates.length" class="chips review-row">
            <span
              v-for="items in duplicateCandidates"
              :key="items.map((item) => item.id).join('-')"
            >
              同品类同色候选：{{ items.map((item) => item.name).join(' / ') }}
            </span>
          </div>
          <div v-if="repeatedOutfitNames.length" class="chips review-row">
            <span v-for="item in repeatedOutfitNames" :key="item">短期高频：{{ item }}</span>
          </div>
          <p>
            重复项只按同品类同色生成检查候选，不自动判断多余；可以补记穿着、尝试新组合，或把确实不合适的物品标为闲置。
          </p>
        </div>
      </section>

      <section v-else-if="activeSection === 'profile'" class="content-section">
        <div class="section-head">
          <div>
            <span>认识自己</span>
            <h2>个人档案与改变尺度</h2>
          </div>
          <span class="autosave-copy">修改后自动保存</span>
        </div>
        <div class="profile-grid">
          <article class="profile-card">
            <h3>身材数据</h3>
            <div class="input-grid">
              <label
                >身高（cm）<input
                  v-model.number="workspace.profile.heightCm"
                  type="number"
                  min="50"
                  max="260" /></label
              ><label
                >体重（kg）<input
                  v-model.number="workspace.profile.weightKg"
                  type="number"
                  min="10"
                  max="500"
                  step="0.1" /></label
              ><label
                >肩宽（cm）<input
                  v-model.number="workspace.profile.shoulderCm"
                  type="number"
                  min="10"
                  max="100"
                  step="0.1" /></label
              ><label
                >腰围（cm）<input
                  v-model.number="workspace.profile.waistCm"
                  type="number"
                  min="20"
                  max="300"
                  step="0.1" /></label
              ><label class="wide"
                >测量日期<input v-model="workspace.profile.measuredOn" type="date"
              /></label>
            </div>
          </article>
          <article class="profile-card">
            <h3>自然特征</h3>
            <div class="input-grid">
              <label
                >肤色<input
                  v-model="workspace.profile.skinTone"
                  maxlength="120"
                  placeholder="如：暖黄、容易晒黑" /></label
              ><label
                >脸型<input
                  v-model="workspace.profile.faceShape"
                  maxlength="120"
                  placeholder="按自己的观察记录" /></label
              ><label
                >发质<input
                  v-model="workspace.profile.hairTexture"
                  maxlength="120"
                  placeholder="如：细软、易塌"
              /></label>
            </div>
          </article>
          <article class="profile-card wide-card">
            <h3>风格与避雷</h3>
            <div class="text-grid">
              <label
                >风格偏好<textarea
                  v-model="workspace.profile.stylePreferences"
                  maxlength="2000"
                  placeholder="清爽、简单、舒适……"
                ></textarea></label
              ><label
                >避雷记录<textarea
                  v-model="workspace.profile.avoidances"
                  maxlength="2000"
                  placeholder="哪些版型、颜色或材质不适合自己，以及真实原因"
                ></textarea></label
              ><label
                >体态目标<textarea
                  v-model="workspace.profile.postureGoal"
                  maxlength="2000"
                  placeholder="精气神、站姿、健康和仪态想保持什么标准"
                ></textarea>
              </label>
            </div>
          </article>
        </div>
        <div class="score-section">
          <div>
            <span>30 / 60 / 80 / 100</span>
            <h2>哪里保持，哪里大胆改变</h2>
          </div>
          <div class="score-grid">
            <article v-for="field in scoreFields" :key="field.key">
              <header>
                <i :class="field.icon"></i>
                <div>
                  <strong>{{ field.label }}</strong
                  ><small>权重 {{ field.weight }}</small>
                </div>
                <em>{{ scoreAdvice(workspace.profile[field.key]) }}</em>
              </header>
              <div class="score-options">
                <button
                  v-for="score in [30, 60, 80, 100]"
                  :key="score"
                  :class="{ selected: workspace.profile[field.key] === score }"
                  @click="setScore(field.key, score)"
                >
                  {{ score }}
                </button>
              </div>
            </article>
          </div>
        </div>
      </section>

      <section v-else-if="activeSection === 'plans'" class="content-section">
        <div class="section-head">
          <div>
            <span>提前决定</span>
            <h2>计划与站内提醒</h2>
          </div>
          <button class="primary" @click="openCreate('plan')">
            <i class="ri-add-line"></i>新增事项
          </button>
        </div>
        <div v-if="overduePlans.length || dueTodayPlans.length" class="due-banner">
          <i class="ri-alarm-warning-line"></i>
          <div>
            <strong>{{
              overduePlans.length
                ? `${overduePlans.length} 项已逾期`
                : `${dueTodayPlans.length} 项今天到期`
            }}</strong>
            <p>截止日期来自你的真实计划；WAO 当前在本页提醒，不声称已向外部渠道推送。</p>
          </div>
        </div>
        <div class="plan-columns">
          <article v-for="type in planTypes" :key="type.value" class="plan-column">
            <header>
              <i :class="type.icon"></i>
              <div>
                <h3>{{ type.label }}</h3>
                <small
                  >{{
                    workspace.plans.filter(
                      (item) => item.type === type.value && item.status === 'OPEN',
                    ).length
                  }}
                  项待办</small
                >
              </div>
            </header>
            <div
              v-if="!workspace.plans.some((item) => item.type === type.value)"
              class="mini-empty"
            >
              还没有记录
            </div>
            <button
              v-for="item in workspace.plans
                .filter((plan) => plan.type === type.value)
                .sort((a, b) => a.dueDate.localeCompare(b.dueDate))"
              :key="item.id"
              class="plan-item"
              :class="{
                done: item.status === 'DONE',
                overdue: item.status === 'OPEN' && item.dueDate < today,
              }"
              @click="togglePlan(item)"
            >
              <i
                :class="
                  item.status === 'DONE'
                    ? 'ri-checkbox-circle-fill'
                    : 'ri-checkbox-blank-circle-line'
                "
              ></i>
              <div>
                <strong>{{ item.title }}</strong
                ><span>{{ item.dueDate }} · {{ planState(item) }}</span
                ><small v-if="item.scenario || item.outfitId"
                  >{{ item.scenario
                  }}{{ item.outfitId ? ` · ${outfitName(item.outfitId)}` : '' }}</small
                >
              </div>
            </button>
          </article>
        </div>
      </section>

      <section v-else class="content-section">
        <div class="section-head">
          <div>
            <span>和谐比昂贵更重要</span>
            <h2>颜色搭配参考与穿搭笔记</h2>
          </div>
          <button class="primary" @click="openCreate('note')">
            <i class="ri-add-line"></i>记录感受
          </button>
        </div>
        <div class="color-guide">
          <article class="tone tone-1">
            <div><span></span><span></span><span></span></div>
            <h3>同色深浅</h3>
            <p>同一色系用明度拉开层次，最稳定、不容易凌乱。</p>
          </article>
          <article class="tone tone-2">
            <div><span></span><span></span><span></span></div>
            <h3>基础色 + 强调色</h3>
            <p>大面积保持中性，只让一个小配件成为视觉重点。</p>
          </article>
          <article class="tone tone-3">
            <div><span></span><span></span><span></span></div>
            <h3>冷暖有主次</h3>
            <p>先确定整体冷暖倾向，再用少量对比色平衡。</p>
          </article>
        </div>
        <p class="guide-boundary">
          <i class="ri-information-line"></i
          >这是通用搭配方法，不会根据肤色自动判断“适合”或“不适合”；真实效果请写进自己的穿着感受和避雷记录。
        </p>
        <div v-if="!workspace.notes.length" class="empty-state compact">
          <i class="ri-sticky-note-line"></i>
          <h3>还没有穿搭笔记</h3>
          <p>磨皮肤、显胖、行动不方便、意外舒服，都值得记下来供下次决定。</p>
        </div>
        <div class="note-grid">
          <article v-for="item in workspace.notes" :key="item.id">
            <header>
              <div>
                <small>{{ new Date(item.createdAt).toLocaleDateString('zh-CN') }}</small>
                <h3>{{ item.title }}</h3>
              </div>
              <button aria-label="删除笔记" @click="removeNote(item)">
                <i class="ri-delete-bin-line"></i>
              </button>
            </header>
            <p>{{ item.content }}</p>
            <div class="chips">
              <span v-for="tag in item.tags" :key="tag">{{ tag }}</span>
            </div>
          </article>
        </div>
      </section>
    </template>

    <div v-if="editorKind" class="modal-backdrop" @click.self="closeEditor(false)">
      <section class="editor-modal" role="dialog" aria-modal="true" :aria-label="modalTitle">
        <header>
          <div>
            <span>形象档案</span>
            <h2>{{ modalTitle }}</h2>
          </div>
          <button aria-label="关闭" @click="closeEditor(false)">
            <i class="ri-close-line"></i>
          </button>
        </header>
        <div v-if="editorKind === 'garment'" class="form-grid">
          <label
            >物品名称<input
              v-model="garmentForm.name"
              maxlength="120"
              placeholder="如：浅蓝牛津纺衬衫" /></label
          ><label
            >品类<input
              v-model="garmentForm.category"
              maxlength="32"
              placeholder="上装、裤子、鞋子、配饰" /></label
          ><label
            >状态<select v-model="garmentForm.status">
              <option v-for="item in statusOptions" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select></label
          ><label>购入日期<input v-model="garmentForm.purchaseDate" type="date" /></label
          ><label
            >季节<input v-model="garmentForm.seasons" placeholder="春、秋；用逗号分隔" /></label
          ><label>颜色<input v-model="garmentForm.colors" placeholder="浅蓝、白" /></label
          ><label>风格<input v-model="garmentForm.styles" placeholder="简约、休闲" /></label
          ><label>场合<input v-model="garmentForm.occasions" placeholder="通勤、约会" /></label
          ><label class="wide"
            >材质<input
              v-model="garmentForm.material"
              maxlength="120"
              placeholder="棉、羊毛……" /></label
          ><label class="wide"
            >真实备注<textarea
              v-model="garmentForm.note"
              maxlength="2000"
              placeholder="舒适度、气味、版型或维护注意事项"
            ></textarea>
          </label>
          <label class="photo-upload wide"
            ><span>物品实拍</span
            ><img
              v-if="garmentForm.imageId"
              :src="appearancePhotoUrl(garmentForm.imageId)"
              alt="物品实拍预览" /><i v-else class="ri-image-add-line"></i
            ><strong>{{
              uploading ? '正在私密上传…' : garmentForm.imageId ? '更换实拍图' : '上传实拍图'
            }}</strong
            ><input
              hidden
              type="file"
              accept="image/jpeg,image/png,image/webp,image/gif"
              :disabled="uploading"
              @change="uploadPhoto($event, 'garment')"
          /></label>
        </div>
        <div v-else-if="editorKind === 'outfit'" class="form-grid">
          <label class="wide"
            >穿搭名称<input
              v-model="outfitForm.name"
              maxlength="120"
              placeholder="如：周一清爽通勤"
          /></label>
          <fieldset class="wide">
            <legend>选择物品</legend>
            <label v-for="item in activeGarments" :key="item.id" class="check"
              ><input v-model="outfitForm.garmentIds" type="checkbox" :value="item.id" /><span
                >{{ item.name }} · {{ item.category }}</span
              ></label
            >
          </fieldset>
          <label class="wide"
            >适用场景<input
              v-model="outfitForm.scenarios"
              placeholder="通勤、约会、聚会、运动、正式商务" /></label
          ><label class="wide"
            >搭配说明<textarea
              v-model="outfitForm.note"
              maxlength="2000"
              placeholder="为什么协调、哪些细节要注意"
            ></textarea></label
          ><label class="photo-upload wide"
            ><span>上身实拍</span
            ><img
              v-if="outfitForm.imageId"
              :src="appearancePhotoUrl(outfitForm.imageId)"
              alt="穿搭实拍预览" /><i v-else class="ri-camera-3-line"></i
            ><strong>{{
              uploading ? '正在私密上传…' : outfitForm.imageId ? '更换上身实拍' : '上传上身实拍'
            }}</strong
            ><input
              hidden
              type="file"
              accept="image/jpeg,image/png,image/webp,image/gif"
              :disabled="uploading"
              @change="uploadPhoto($event, 'outfit')"
          /></label>
        </div>
        <div v-else-if="editorKind === 'wear'" class="form-grid">
          <label>穿着日期<input v-model="wearForm.date" type="date" :max="today" /></label
          ><label
            >穿搭组合<select v-model="wearForm.outfitId">
              <option value="">不选固定组合</option>
              <option v-for="item in workspace.outfits" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select></label
          >
          <fieldset class="wide">
            <legend>补充散件（可选）</legend>
            <label v-for="item in activeGarments" :key="item.id" class="check"
              ><input v-model="wearForm.garmentIds" type="checkbox" :value="item.id" /><span>{{
                item.name
              }}</span></label
            >
          </fieldset>
          <label class="wide"
            >穿着感受<textarea
              v-model="wearForm.feeling"
              maxlength="2000"
              placeholder="舒适、磨皮肤、显胖、行动方便……只写真实感受"
            ></textarea>
          </label>
        </div>
        <div v-else-if="editorKind === 'plan'" class="form-grid">
          <label
            >事项类型<select v-model="planForm.type">
              <option v-for="item in planTypes" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select></label
          ><label>截止 / 场合日期<input v-model="planForm.dueDate" type="date" /></label
          ><label class="wide"
            >事项标题<input
              v-model="planForm.title"
              maxlength="160"
              placeholder="如：周五客户见面提前定穿搭" /></label
          ><label
            >场景<input
              v-model="planForm.scenario"
              maxlength="120"
              placeholder="通勤、约会、换季……" /></label
          ><label
            >预选穿搭<select v-model="planForm.outfitId">
              <option value="">暂不选择</option>
              <option v-for="item in workspace.outfits" :key="item.id" :value="item.id">
                {{ item.name }}
              </option>
            </select></label
          ><label class="wide"
            >备注<textarea
              v-model="planForm.note"
              maxlength="2000"
              placeholder="需要准备、清洗或采购什么"
            ></textarea>
          </label>
        </div>
        <div v-else class="form-grid">
          <label class="wide"
            >笔记标题<input
              v-model="noteForm.title"
              maxlength="160"
              placeholder="如：深色直筒裤比修身版更自在" /></label
          ><label class="wide"
            >真实感受<textarea
              v-model="noteForm.content"
              maxlength="5000"
              placeholder="记录穿着时发生了什么、以后怎么选"
            ></textarea></label
          ><label class="wide"
            >标签<input v-model="noteForm.tags" placeholder="版型、颜色、舒适度；用逗号分隔"
          /></label>
        </div>
        <footer>
          <span>未提交表单会保存在本机，下次打开可继续。</span>
          <div>
            <button @click="closeEditor(false)">稍后再填</button
            ><button class="primary" :disabled="uploading" @click="submitEditor">
              {{ uploading ? '等待图片上传' : '保存' }}
            </button>
          </div>
        </footer>
      </section>
    </div>
  </main>
</template>

<style scoped>
.appearance-page {
  --ink: #263331;
  --muted: #6f7c78;
  --line: #dfe5df;
  --cream: #f6f5ef;
  --sage: #496a60;
  --sage-soft: #e6eee9;
  --amber: #c37b2c;
  color: var(--ink);
  padding: 28px 32px 70px;
  max-width: 1500px;
  margin: 0 auto;
}
.hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 220px;
  gap: 28px;
  padding: 34px;
  border-radius: 24px;
  background: linear-gradient(135deg, #e8eee9 0%, #f7f3e8 58%, #eee2cf 100%);
  border: 1px solid rgba(73, 106, 96, 0.15);
  box-shadow: 0 18px 50px rgba(41, 61, 53, 0.08);
}
.eyebrow,
.section-head span,
.hero-score span,
.score-section > div:first-child > span,
.review-card > div > span,
.editor-modal header span {
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.16em;
  color: var(--sage);
}
.hero h1 {
  font-family: serif;
  font-size: 46px;
  margin: 6px 0 8px;
}
.hero p {
  max-width: 760px;
  margin: 0;
  color: #53625e;
  line-height: 1.7;
}
.hero textarea {
  width: 100%;
  min-height: 58px;
  margin-top: 20px;
  padding: 13px 15px;
  border: 1px solid rgba(73, 106, 96, 0.2);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.58);
  color: var(--ink);
  resize: vertical;
  line-height: 1.6;
}
.hero-score {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 20px;
  border-radius: 50% 50% 46% 54%/55% 48% 52% 45%;
  background: rgba(255, 255, 255, 0.68);
  text-align: center;
  aspect-ratio: 1;
}
.hero-score strong {
  font-family: serif;
  font-size: 62px;
  line-height: 1;
  color: var(--sage);
}
.hero-score small {
  max-width: 145px;
  color: var(--muted);
  line-height: 1.45;
  margin-top: 8px;
}
.save-strip {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 6px 12px;
  color: var(--muted);
  font-size: 13px;
}
.save-strip.error {
  color: #a04e35;
}
.save-strip button {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: #a04e35;
  text-decoration: underline;
  cursor: pointer;
}
.overview-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin: 8px 0 22px;
}
.overview-grid article {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: 15px;
  background: var(--wao-bg-elevated, #fff);
}
.overview-grid i {
  font-size: 23px;
  color: var(--sage);
}
.overview-grid strong {
  font-size: 24px;
}
.overview-grid span {
  display: block;
  font-size: 12px;
  color: var(--muted);
}
.overview-grid .warning i,
.overview-grid .warning strong {
  color: var(--amber);
}
.section-tabs {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 4px;
  margin-bottom: 20px;
  scrollbar-width: thin;
}
.section-tabs button {
  white-space: nowrap;
  border: 1px solid var(--line);
  background: var(--wao-bg-elevated, #fff);
  padding: 11px 16px;
  border-radius: 12px;
  color: var(--muted);
  cursor: pointer;
}
.section-tabs button i {
  margin-right: 7px;
}
.section-tabs button.active {
  background: var(--sage);
  border-color: var(--sage);
  color: #fff;
}
.content-section {
  min-height: 420px;
}
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 18px;
  margin: 18px 0;
}
.section-head h2,
.score-section h2 {
  margin: 4px 0 0;
  font-family: serif;
  font-size: 28px;
}
.primary,
.section-head button,
.head-actions button,
.empty-state button,
.state-card button,
.editor-modal footer button {
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--wao-bg-elevated, #fff);
  padding: 10px 15px;
  color: var(--ink);
  cursor: pointer;
}
.primary {
  background: var(--sage) !important;
  border-color: var(--sage) !important;
  color: #fff !important;
}
.head-actions {
  display: flex;
  gap: 8px;
}
.filters {
  display: grid;
  grid-template-columns: minmax(240px, 1fr) 180px 180px;
  gap: 10px;
  margin-bottom: 18px;
}
.filters input,
.filters select,
.form-grid input,
.form-grid select,
.form-grid textarea,
.profile-card input,
.profile-card textarea {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 11px 12px;
  background: var(--wao-bg-elevated, #fff);
  color: var(--ink);
}
.empty-state,
.state-card {
  display: grid;
  place-items: center;
  text-align: center;
  gap: 8px;
  min-height: 280px;
  border: 1px dashed #ccd5ce;
  border-radius: 18px;
  background: var(--cream);
  padding: 30px;
}
.empty-state i,
.state-card > i {
  font-size: 38px;
  color: #8ca096;
}
.empty-state h3,
.empty-state p {
  margin: 0;
}
.empty-state p {
  color: var(--muted);
}
.empty-state.compact {
  min-height: 130px;
  margin-bottom: 14px;
}
.state-card.error {
  color: #a04e35;
}
.garment-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(235px, 1fr));
  gap: 16px;
}
.garment-card,
.outfit-card,
.profile-card,
.score-grid article,
.plan-column,
.note-grid article,
.review-card,
.wear-calendar {
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--wao-bg-elevated, #fff);
  overflow: hidden;
}
.photo {
  height: 205px;
  position: relative;
  background: #edf0eb;
  display: grid;
  place-items: center;
}
.photo img,
.outfit-photo img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.photo > i {
  font-size: 48px;
  color: #9aaba5;
}
.photo > span {
  position: absolute;
  left: 10px;
  bottom: 10px;
  padding: 5px 8px;
  border-radius: 7px;
  background: rgba(31, 47, 42, 0.78);
  color: #fff;
  font-size: 11px;
}
.card-body {
  padding: 16px;
}
.card-title {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.card-title h3,
.outfit-card h3,
.note-grid h3 {
  margin: 3px 0 10px;
}
.card-title small,
.outfit-card small,
.note-grid small {
  color: var(--sage);
}
.card-actions {
  display: flex;
  gap: 4px;
}
.card-actions button,
.wear-calendar button,
.note-grid header button {
  border: 0;
  background: transparent;
  color: var(--muted);
  cursor: pointer;
  padding: 5px;
}
.card-actions.text button {
  font-size: 12px;
  padding: 4px 0;
  margin-right: 10px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chips span {
  padding: 4px 8px;
  border-radius: 7px;
  background: var(--sage-soft);
  color: var(--sage);
  font-size: 11px;
}
.wear-stat {
  display: flex;
  align-items: baseline;
  gap: 5px;
  margin-top: 15px;
  color: var(--muted);
  font-size: 12px;
}
.wear-stat strong {
  font-size: 20px;
  color: var(--ink);
}
.wear-stat small {
  margin-left: auto;
}
.card-body > p {
  font-size: 12px;
  color: var(--muted);
  line-height: 1.6;
}
.split-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 370px;
  gap: 20px;
}
.outfit-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}
.outfit-card {
  display: grid;
  grid-template-columns: 120px 1fr;
  min-height: 160px;
}
.outfit-photo {
  background: #e9eeea;
  display: grid;
  place-items: center;
}
.outfit-photo > i {
  font-size: 42px;
  color: #9bad9f;
}
.outfit-card > div:last-child {
  padding: 16px;
  min-width: 0;
}
.outfit-card p,
.outfit-card blockquote {
  font-size: 12px;
  color: var(--muted);
  line-height: 1.55;
  margin: 7px 0;
}
.outfit-card blockquote {
  border-left: 2px solid #bac7be;
  padding-left: 8px;
}
.wear-calendar {
  padding: 18px;
}
.aside-head {
  display: flex;
  justify-content: space-between;
}
.aside-head h3 {
  margin: 3px 0 14px;
}
.aside-head span {
  font-size: 11px;
  color: var(--sage);
}
.wear-calendar > article {
  display: grid;
  grid-template-columns: 92px 1fr 24px;
  gap: 10px;
  padding: 12px 0;
  border-top: 1px solid var(--line);
}
.wear-calendar time {
  font-size: 11px;
  color: var(--sage);
}
.wear-calendar strong {
  font-size: 13px;
}
.wear-calendar p {
  font-size: 11px;
  color: var(--muted);
  margin: 4px 0;
}
.mini-empty {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
  padding: 18px 0;
}
.review-card {
  padding: 20px;
  margin-top: 20px;
}
.review-card h3 {
  margin: 4px 0 14px;
}
.review-card > p {
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 0;
}
.review-row {
  margin-top: 8px;
}
.profile-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.profile-card {
  padding: 20px;
}
.profile-card.wide-card {
  grid-column: 1/-1;
}
.profile-card h3 {
  margin: 0 0 15px;
}
.input-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.input-grid label,
.text-grid label,
.form-grid > label {
  font-size: 12px;
  color: var(--muted);
  display: grid;
  gap: 6px;
}
.input-grid .wide {
  grid-column: 1/-1;
}
.text-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.text-grid textarea {
  min-height: 110px;
  resize: vertical;
}
.autosave-copy {
  font-size: 12px;
  color: var(--muted);
}
.score-section {
  margin-top: 28px;
}
.score-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-top: 14px;
}
.score-grid article {
  padding: 16px;
}
.score-grid header {
  display: flex;
  align-items: center;
  gap: 10px;
}
.score-grid header i {
  font-size: 22px;
  color: var(--sage);
}
.score-grid header div {
  display: grid;
}
.score-grid small {
  color: var(--muted);
  font-size: 10px;
}
.score-grid em {
  margin-left: auto;
  font-style: normal;
  font-size: 11px;
  color: var(--sage);
}
.score-options {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 5px;
  margin-top: 14px;
}
.score-options button {
  border: 1px solid var(--line);
  background: transparent;
  padding: 7px;
  border-radius: 7px;
  color: var(--muted);
  cursor: pointer;
}
.score-options button.selected {
  background: var(--sage);
  color: #fff;
  border-color: var(--sage);
}
.due-banner {
  display: flex;
  align-items: center;
  gap: 13px;
  background: #fff6e8;
  border: 1px solid #edc994;
  border-radius: 14px;
  padding: 15px 18px;
  margin-bottom: 16px;
}
.due-banner i {
  font-size: 25px;
  color: var(--amber);
}
.due-banner p {
  margin: 3px 0 0;
  color: #84633d;
  font-size: 12px;
}
.plan-columns {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.plan-column {
  padding: 16px;
}
.plan-column > header {
  display: flex;
  align-items: center;
  gap: 10px;
  border-bottom: 1px solid var(--line);
  padding-bottom: 12px;
}
.plan-column > header i {
  font-size: 23px;
  color: var(--sage);
}
.plan-column h3 {
  margin: 0;
}
.plan-column header small {
  color: var(--muted);
}
.plan-item {
  display: flex;
  width: 100%;
  gap: 9px;
  text-align: left;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: transparent;
  padding: 13px 2px;
  color: var(--ink);
  cursor: pointer;
}
.plan-item > i {
  color: var(--sage);
  margin-top: 2px;
}
.plan-item div {
  display: grid;
  gap: 3px;
}
.plan-item span,
.plan-item small {
  font-size: 11px;
  color: var(--muted);
}
.plan-item.done {
  text-decoration: line-through;
  opacity: 0.55;
}
.plan-item.overdue span {
  color: #b35839;
}
.color-guide {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 15px;
}
.color-guide article {
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--wao-bg-elevated, #fff);
}
.tone > div {
  display: flex;
  height: 74px;
  border-radius: 10px;
  overflow: hidden;
}
.tone > div span {
  flex: 1;
}
.tone-1 span:nth-child(1) {
  background: #d8e0e6;
}
.tone-1 span:nth-child(2) {
  background: #7790a2;
}
.tone-1 span:nth-child(3) {
  background: #344b5b;
}
.tone-2 span:nth-child(1) {
  background: #e9e4da;
}
.tone-2 span:nth-child(2) {
  background: #444744;
}
.tone-2 span:nth-child(3) {
  background: #b06542;
}
.tone-3 span:nth-child(1) {
  background: #536b83;
}
.tone-3 span:nth-child(2) {
  background: #d9c9af;
}
.tone-3 span:nth-child(3) {
  background: #8c5943;
}
.tone h3 {
  margin: 14px 0 6px;
}
.tone p,
.guide-boundary {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.6;
  margin: 0;
}
.guide-boundary {
  margin: 12px 0 20px;
}
.note-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
}
.note-grid article {
  padding: 18px;
}
.note-grid header {
  display: flex;
  justify-content: space-between;
}
.note-grid p {
  white-space: pre-wrap;
  color: #52605c;
  line-height: 1.65;
}
.modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(24, 34, 30, 0.55);
  display: grid;
  place-items: center;
  padding: 22px;
}
.editor-modal {
  width: min(720px, 100%);
  max-height: min(860px, calc(100vh - 44px));
  overflow: auto;
  background: var(--wao-bg-elevated, #fff);
  border-radius: 20px;
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.24);
}
.editor-modal > header {
  position: sticky;
  top: 0;
  z-index: 2;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid var(--line);
  background: var(--wao-bg-elevated, #fff);
}
.editor-modal h2 {
  margin: 3px 0 0;
}
.editor-modal header button {
  border: 0;
  background: transparent;
  font-size: 22px;
  color: var(--muted);
  cursor: pointer;
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 15px;
  padding: 22px 24px;
}
.form-grid .wide,
.form-grid fieldset {
  grid-column: 1/-1;
}
.form-grid textarea {
  min-height: 90px;
  resize: vertical;
}
.form-grid fieldset {
  border: 1px solid var(--line);
  border-radius: 12px;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  padding: 14px;
}
.form-grid legend {
  font-size: 12px;
  color: var(--muted);
  padding: 0 5px;
}
.check {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
}
.check input {
  width: auto;
}
.photo-upload {
  min-height: 150px;
  border: 1px dashed #aebeb4;
  border-radius: 12px;
  display: grid !important;
  place-items: center;
  align-content: center;
  gap: 7px !important;
  overflow: hidden;
  cursor: pointer;
}
.photo-upload img {
  max-height: 180px;
  max-width: 100%;
  border-radius: 9px;
}
.photo-upload > i {
  font-size: 32px;
  color: #91a399;
}
.editor-modal footer {
  position: sticky;
  bottom: 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 15px;
  padding: 16px 24px;
  border-top: 1px solid var(--line);
  background: var(--wao-bg-elevated, #fff);
}
.editor-modal footer > span {
  font-size: 11px;
  color: var(--muted);
}
.editor-modal footer > div {
  display: flex;
  gap: 8px;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 1000px) {
  .split-layout {
    grid-template-columns: 1fr;
  }
  .plan-columns {
    grid-template-columns: 1fr 1fr;
  }
  .score-grid {
    grid-template-columns: 1fr 1fr;
  }
}
@media (max-width: 700px) {
  .appearance-page {
    padding: 16px 14px 80px;
  }
  .hero {
    grid-template-columns: 1fr;
    padding: 23px;
  }
  .hero h1 {
    font-size: 38px;
  }
  .hero-score {
    width: 170px;
    justify-self: center;
  }
  .overview-grid {
    grid-template-columns: 1fr 1fr;
  }
  .section-head {
    align-items: flex-start;
  }
  .filters,
  .profile-grid,
  .text-grid,
  .color-guide,
  .plan-columns,
  .score-grid {
    grid-template-columns: 1fr;
  }
  .profile-card.wide-card {
    grid-column: auto;
  }
  .head-actions {
    flex-wrap: wrap;
    justify-content: flex-end;
  }
  .outfit-card {
    grid-template-columns: 95px 1fr;
  }
  .form-grid {
    grid-template-columns: 1fr;
    padding: 18px;
  }
  .form-grid .wide,
  .form-grid fieldset {
    grid-column: auto;
  }
  .form-grid fieldset {
    grid-template-columns: 1fr;
  }
  .editor-modal footer {
    align-items: flex-start;
    flex-direction: column;
  }
  .editor-modal footer > div {
    width: 100%;
    justify-content: flex-end;
  }
  .modal-backdrop {
    padding: 8px;
  }
  .editor-modal {
    max-height: calc(100vh - 16px);
    border-radius: 15px;
  }
  .wear-stat {
    flex-wrap: wrap;
  }
  .wear-stat small {
    width: 100%;
    margin-left: 0;
  }
}
</style>
