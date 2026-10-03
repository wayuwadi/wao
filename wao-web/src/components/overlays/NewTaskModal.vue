<script setup lang="ts">
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { usePreferenceStore } from '@/stores/preferences'
import { toast } from '@/composables/toast'
// 异步加载，理由同 TaskDrawer：ProseMirror 不进首屏包。
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
import type { Priority, WorkItemType } from '@/types'

const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const preferences = usePreferenceStore()

/**
 * 快捷截止选项按真实「今天」推算：今天 / 明天 / 本周日 / 下周一；ISO 日期（日历双击带入）原样返回。
 * 「不设置」返回空串；截止日期与执行安排独立。
 */
function dueDate(label: string): string {
  if (label === NO_DUE) return ''
  if (/^\d{4}-\d{2}-\d{2}$/.test(label)) return label
  const d = new Date(`${data.today}T00:00:00`)
  const dow = d.getDay() || 7
  if (label === '明天') d.setDate(d.getDate() + 1)
  else if (label === '周日') d.setDate(d.getDate() + (7 - dow))
  else if (label === '下周一') d.setDate(d.getDate() + (8 - dow))
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

const NO_DUE = '不设置'
const route = useRoute()
const contextProject = ui.modalProject || (route?.name === 'project' ? String(route.params.id || '') : '')
const source = ui.modalTaskSeed
const LEGACY_DRAFT_KEY = source?.draftKey || (contextProject ? `wao-new-task-project-${contextProject}-draft-v1` : '') || (ui.modalPriority
  ? `wao-new-task-quadrant-${ui.modalPriority}-draft-v1` : 'wao-new-task-draft-v1')
const draftAccount = session.account?.accountId ?? session.userId
const DRAFT_KEY = `${LEGACY_DRAFT_KEY}:account:${draftAccount}`
const legacyDraftAvailable = ref(false)
try { legacyDraftAvailable.value = !!localStorage.getItem(LEGACY_DRAFT_KEY) } catch { /* Keep the current form usable. */ }
type Draft = {
  workItemType?: WorkItemType
  title: string; project: string; owner: string; pr: Priority; due: string; desc: string
  scheduleMode: 'deadline' | 'date' | 'range'; start?: string; startAt: string; endAt: string
  pendingTaskId?: string | null
}
function loadDraft(): Partial<Draft> {
  try { return JSON.parse(localStorage.getItem(DRAFT_KEY) || '{}') } catch { return {} }
}
const draft = loadDraft()
/**
 * 项目数据会在应用启动后由真实接口替换 fixtures，不能在弹窗创建时截取一份静态数组。
 * 否则首页入口被快速点击时，会继续显示并提交已经被替换掉的演示项目。
 */
const openProjects = computed(() => data.projects.filter((p) => p.status !== '已完成'))
const title = ref(draft.title ?? source?.title ?? '')
// modalProject 未预选时是空串（不是 null），必须用 || 兜底到第一个进行中的项目，
// 否则从顶栏全局入口建出的任务 project 为空，在所有项目列表里都看不见。
const project = ref(
  draft.project || contextProject || (source ? '' : preferences.defaultProject || openProjects.value[0]?.id || ''),
)

/**
 * 真实项目列表到达后，只有当前预选项目不在列表中时才回退到首个可用项目。
 * 这样既能清理 fixtures / 过期草稿留下的伪 id，也不会覆盖用户已经选中的真实项目。
 */
const projectChosenByUser = ref(false)
watch(openProjects, (projects) => {
  if (!projectChosenByUser.value && !draft.project && contextProject && projects.some(item => item.id === contextProject)) {
    project.value = contextProject
    return
  }
  if (!projects.some((item) => item.id === project.value)) {
    if (source) {
      project.value = projects.some((item) => item.id === ui.modalProject) ? ui.modalProject : ''
      return
    }
    project.value = projects.some((item) => item.id === contextProject) ? contextProject
      : projects.some((item) => item.id === preferences.defaultProject)
      ? preferences.defaultProject
      : projects[0]?.id || ''
  }
}, { immediate: true })
watch(() => preferences.defaultProject, (value) => {
  if (!source && !draft.project && !contextProject && value && openProjects.value.some((item) => item.id === value)) {
    project.value = value
  }
})
const owner = ref(draft.pendingTaskId ? draft.owner || session.currentUser.id : session.currentUser.id)
const workItemType = ref<WorkItemType>(draft.workItemType ?? 'task')
const pr = ref<Priority>(draft.pendingTaskId ? draft.pr || ui.modalPriority || 'P2' : ui.modalPriority || 'P2')
// 只有明确的截止预设才带入此字段；日历使用独立执行安排预设。
const QUICK_DUES = [NO_DUE, '今天', '明天', '周日', '下周一']
const due = ref(ui.modalDue || draft.due || NO_DUE)
const dueOptions = computed(() => (ui.modalDue ? [ui.modalDue, ...QUICK_DUES] : QUICK_DUES))
const scheduleMode = ref<'deadline' | 'date' | 'range'>(
  ui.modalStartAt ? 'range' : ui.modalScheduleDate ? 'date' : draft.scheduleMode || 'deadline',
)
const start = ref(ui.modalScheduleDate || draft.start || '')
const startAt = ref(ui.modalStartAt || draft.startAt || `${dueDate(due.value) || data.today}T09:00`)
const endAt = ref(ui.modalEndAt || draft.endAt || `${dueDate(due.value) || data.today}T10:00`)
const desc = ref(draft.desc ?? source?.desc ?? '')
const draftState = ref<'saving' | 'saved' | 'error'>('saved')
const createState = ref<'idle' | 'submitting' | 'error'>('idle')
const pendingTaskId = ref<string | null>(draft.pendingTaskId ?? null)
let draftTimer: ReturnType<typeof setTimeout> | null = null

function saveDraft() {
  draftState.value = 'saving'
  if (draftTimer) clearTimeout(draftTimer)
  try {
    localStorage.setItem(DRAFT_KEY, JSON.stringify({
      title: title.value, project: project.value, owner: owner.value,
      workItemType: workItemType.value, pr: pr.value, due: due.value, desc: desc.value,
      scheduleMode: scheduleMode.value, start: start.value, startAt: startAt.value, endAt: endAt.value,
      pendingTaskId: pendingTaskId.value,
    } satisfies Draft))
  } catch {
    draftState.value = 'error'
    return
  }
  draftTimer = setTimeout(() => (draftState.value = 'saved'), 180)
}
function restoreLegacyDraft() {
  if (title.value || desc.value || pendingTaskId.value) return
  try {
    const old = JSON.parse(localStorage.getItem(LEGACY_DRAFT_KEY) || '{}') as Partial<Draft>
    title.value = typeof old.title === 'string' ? old.title : ''
    desc.value = typeof old.desc === 'string' ? old.desc : ''
    if (typeof old.project === 'string' && openProjects.value.some(p => p.id === old.project)) project.value = old.project
    if (old.due && QUICK_DUES.includes(old.due)) due.value = old.due
    if (old.scheduleMode === 'range') scheduleMode.value = 'range'
    if (typeof old.startAt === 'string') startAt.value = old.startAt
    if (typeof old.endAt === 'string') endAt.value = old.endAt
    saveDraft()
    legacyDraftAvailable.value = false
  } catch { toast('旧版草稿无法读取，原记录仍保留', 'ri-error-warning-line') }
}
watch([title, project, owner, workItemType, pr, due, desc, scheduleMode, start, startAt, endAt], saveDraft, { flush: 'sync', immediate: !!source })

onBeforeUnmount(() => { if (draftTimer) clearTimeout(draftTimer) })

/**
 * 打开就把光标放进标题框。
 *
 * <p>手机上这一下最值钱：录任务的第一件事一定是敲标题，少一次点击就少一次「点歪了点到别处」，
 * 键盘也跟着直接弹出来。已经有草稿标题时不抢焦点，免得手一抖把恢复出来的内容顶掉。
 */
const titleInput = ref<HTMLInputElement | null>(null)
onMounted(() => {
  if (!title.value) nextTick(() => titleInput.value?.focus())
})

/**
 * 编辑器里粘贴 / 插入的图片：存进图床，再把地址回传给编辑器内联到光标处，
 * 不再堆成底部的附件缩略图。
 */
async function onUploadImage(file: File, done: (url: string | null, name: string) => void) {
  if (file.size > data.MAX_IMAGE_BYTES) {
    toast('图片超过 10MB，已跳过', 'ri-error-warning-line')
    done(null, '')
    return
  }
  const ref = await data.uploadImage(file, '新建任务')
  if (!ref) toast('图片上传失败，请重试', 'ri-error-warning-line')
  done(ref?.url ?? null, ref?.name ?? file.name)
}

async function create() {
  if (createState.value === 'submitting') return
  if ((session.account?.accountId ?? session.userId) !== draftAccount) {
    toast('登录账号已变化，请切回原账号继续处理草稿', 'ri-error-warning-line')
    return
  }
  const name = title.value.trim()
  if (!name) return
  if (!project.value) {
    toast('请先选择所属项目', 'ri-error-warning-line')
    return
  }
  if (scheduleMode.value === 'range' && (!startAt.value || !endAt.value || endAt.value <= startAt.value)) {
    toast('结束时间必须晚于开始时间', 'ri-error-warning-line')
    return
  }
  if (scheduleMode.value === 'date' && !start.value) {
    toast('请选择执行日期，或选择不安排', 'ri-error-warning-line')
    return
  }
  const payload = {
    title: name, project: project.value, workItemType: workItemType.value, pr: pr.value, owner: owner.value,
    due: dueDate(due.value),
    start: scheduleMode.value === 'range' ? startAt.value.slice(0, 10) : scheduleMode.value === 'date' ? start.value : '',
    startAt: scheduleMode.value === 'range' ? startAt.value : null,
    endAt: scheduleMode.value === 'range' ? endAt.value : null,
    desc: desc.value.trim(), images: [],
    docs: source?.sourceDocId ? [source.sourceDocId] : [],
  }
  createState.value = 'submitting'
  let persisted = false
  let createdId = pendingTaskId.value
  if (pendingTaskId.value && data.taskById(pendingTaskId.value)) {
    // 首次写入失败后复用同一条本地任务，不重复插入；失败后改过的内容也一起补写。
    data.patchTask(pendingTaskId.value, payload)
    await data.flushTask(pendingTaskId.value)
    persisted = data.taskSaveState(pendingTaskId.value) === 'saved'
  } else {
    pendingTaskId.value ??= `t${crypto.randomUUID().replace(/-/g, '').slice(0, 31)}`
    saveDraft()
    if (draftState.value === 'error') {
      createState.value = 'error'
      toast('草稿无法写入本机，本次尚未创建，请保持弹窗打开', 'ri-error-warning-line')
      return
    }
    const result = await data.addTask(payload, pendingTaskId.value)
    createdId = result.task.id
    pendingTaskId.value = result.task.id
    saveDraft()
    persisted = result.persisted
  }
  if (!persisted) {
    createState.value = 'error'
    toast(data.taskSaveError(pendingTaskId.value ?? '') || '保存尚未确认，输入已保留，请检查网络后重试', 'ri-error-warning-line')
    return
  }
  try { localStorage.removeItem(DRAFT_KEY) } catch { /* 服务器已确认保存，不阻止关闭表单。 */ }
  ui.closeModal()
  if (createdId && ui.modalOpenTaskAfterCreate) ui.openTask(createdId)
  toast(`任务「${name}」已创建`, 'ri-checkbox-circle-fill')
  ui.celebrate()
}

async function makeDefaultProject() {
  if (!project.value || project.value === preferences.defaultProject) return
  try {
    await preferences.setDefaultProject(project.value)
    toast('以后新建任务会默认选择这个项目', 'ri-pushpin-line')
  } catch {
    toast('默认项目保存失败，请重试', 'ri-error-warning-line')
  }
}

/** 写入正在飞行时不允许误关弹窗；失败后仍可关闭，草稿和本地任务都会保留。 */
async function requestClose() {
  if (createState.value === 'submitting') return
  if (title.value.trim()) await create()
  else if (desc.value.trim()) toast('请填写标题后保存，正文已保留', 'ri-information-line')
  else ui.closeModal()
}
useOverlayDismiss(() => true, requestClose)
</script>

<template>
  <div class="mask" @click="requestClose"></div>
  <div class="modal task">
    <div class="head">
      <div class="title">{{ source ? '添加到待办' : '新建任务' }}</div>
      <div class="spacer"></div>
      <button class="close" :disabled="createState === 'submitting'" @click="requestClose"><i class="ri-close-line"></i></button>
    </div>
    <div class="modal-body">
    <input
      ref="titleInput"
      v-model="title"
      class="title-input"
      enterkeyhint="done"
      placeholder="要完成什么？例如：整理证件盒"
      @keydown.enter.prevent="create"
    />
    <p v-if="legacyDraftAvailable" class="source-note">旧版草稿仍保存在此浏览器，因缺少账号标识未自动加载。
      <button type="button" :disabled="!!title || !!desc || !!pendingTaskId" @click="restoreLegacyDraft">恢复旧版草稿</button>
    </p>

    <p v-if="source" class="source-note">来自：<a v-if="source.sourceUrl" :href="source.sourceUrl" target="_blank" rel="noopener noreferrer">{{ source.sourceTitle }}</a><span v-else>{{ source.sourceTitle }}</span>。原文保留不变。</p>
    <div class="meta-row">
      <label class="meta-field"><span>项目</span><select v-model="project" @change="projectChosenByUser = true" aria-label="所属项目" class="compact-select">
        <option value="" disabled>请选择项目</option>
        <option v-for="p in openProjects" :key="p.id" :value="p.id">{{ p.name }}</option>
      </select></label>
      <label class="meta-field type-field"><span>类型</span><select v-model="workItemType" aria-label="工作项类型" class="compact-select"><option value="requirement">需求</option><option value="task">任务</option><option value="bug">缺陷</option></select></label>
      <button
        v-if="project"
        class="default-project"
        :class="{ on: preferences.defaultProject === project }"
        type="button"
        :aria-label="preferences.defaultProject === project ? '当前默认项目' : '设为默认项目'"
        :title="preferences.defaultProject === project ? '当前默认项目' : '设为默认项目'"
        :disabled="preferences.defaultProject === project"
        @click="makeDefaultProject"
      >
        <i class="ri-pushpin-line"></i>
      </button>
    </div>
    <p v-if="source && !project" class="label-hint">请选择要存入的项目</p>
    <details class="schedule-details" :open="!!ui.modalScheduleDate || !!ui.modalDue || !!ui.modalStartAt || undefined">
      <summary><i class="ri-calendar-line" aria-hidden="true"></i>时间安排 <span>{{ scheduleMode === 'range' ? '执行时段' : scheduleMode === 'date' ? '执行日期' : '未安排' }} · {{ due === NO_DUE ? '无截止日期' : due }}</span></summary>
      <div class="label">截止日期</div>
      <div class="pills">
        <button v-for="d in dueOptions" :key="d" class="pill sq" :class="{ on: due === d }" @click="due = d">{{ d }}</button>
        <input aria-label="截止日期" type="date" :value="dueDate(due)" @change="due = ($event.target as HTMLInputElement).value || NO_DUE" />
      </div>
      <div class="label">执行安排 · 不改变截止</div>
      <div class="schedule-switch">
        <button :class="{ on: scheduleMode === 'deadline' }" @click="scheduleMode = 'deadline'">不安排</button>
        <button :class="{ on: scheduleMode === 'date' }" @click="scheduleMode = 'date'">指定日期</button>
        <button :class="{ on: scheduleMode === 'range' }" @click="scheduleMode = 'range'">时间段</button>
      </div>
      <label v-if="scheduleMode === 'date'">执行日期（不指定时间）<input v-model="start" aria-label="执行日期" type="date" /></label>
      <div v-if="scheduleMode === 'range'" class="range-fields">
        <label><span>开始</span><input v-model="startAt" type="datetime-local" /></label>
        <label><span>结束</span><input v-model="endAt" type="datetime-local" /></label>
      </div>
    </details>

    <div class="label">描述 <span class="label-hint">敲 ## 变标题、- 变列表；粘贴或拖入的截图会插在光标处</span></div>
    <div class="desc-wrap">
      <MarkdownEditor
        v-model="desc"
        placeholder="补充背景、目标与边界…可直接 Ctrl/Cmd+V 粘贴截图"
        :min-height="110"
        @upload-image="onUploadImage"
      />
    </div>
    </div>

    <div class="foot">
      <span v-if="createState === 'error'" class="create-error" role="alert"><i class="ri-error-warning-line"></i>{{ data.taskSaveError(pendingTaskId ?? '') || '保存尚未确认，输入仍保留在当前窗口' }}</span>
      <span v-else-if="draftState === 'error'" class="draft-state" role="alert">草稿未能写入本机，请保持弹窗打开。<button type="button" @click="saveDraft">重试</button></span>
      <span v-else class="draft-state"><i :class="draftState === 'saving' ? 'ri-loader-4-line spin' : 'ri-checkbox-circle-line'"></i>{{ draftState === 'saving' ? '正在保存草稿' : '草稿已保存' }}</span>
      <div class="spacer"></div>
      <button class="btn-ghost" :disabled="createState === 'submitting'" @click="requestClose">完成并关闭</button>
      <button class="btn-primary" :disabled="!title.trim() || createState === 'submitting'" @click="create">
        <i v-if="createState === 'submitting'" class="ri-loader-4-line spin"></i>
        {{ createState === 'submitting' ? '正在创建' : createState === 'error' ? '重试创建' : '创建任务' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.meta-row { display: flex; gap: 8px; align-items: end; margin-top: 12px; }
.meta-field { display: grid; gap: 5px; min-width: 0; flex: 1; color: var(--wao-text-weak); font-size: 11px; }
.type-field { flex: 0 0 110px; }
.default-project { flex: none; height: 34px; width: 34px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text-weak); cursor: pointer; }
.default-project.on { color: var(--wao-primary-text); background: var(--wao-primary-soft); }
.default-project:disabled { cursor: default; }
.schedule-details { margin-top: 12px; padding: 9px 10px; border: 1px solid var(--wao-line-control); border-radius: 8px; }
.schedule-details summary { display: flex; align-items: center; gap: 7px; color: var(--wao-text-2); font-size: 12px; cursor: pointer; list-style: none; }
.schedule-details summary::-webkit-details-marker { display: none; }
.schedule-details summary span { margin-left: auto; color: var(--wao-text-faint); }
.compact-select { width: 100%; min-width: 0; padding: 8px; border: 1px solid var(--wao-line); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text); }
.source-note { margin: 8px 0 14px; font-size: 12px; line-height: 1.6; color: var(--wao-text-weak); overflow-wrap: anywhere; }
.source-note a { color: var(--wao-primary-text); }
.mask {
  position: fixed;
  inset: 0;
  background: rgba(23, 25, 35, 0.32);
  z-index: 100;
  animation: waoFade 0.15s ease;
}
.modal {
  position: fixed;
  top: 6dvh;
  /* 用 margin 居中而不是 transform：waoPop 动画也动 transform，
     两者叠在一起时居中位移会被覆盖，卡片先偏右出现再跳回中间。 */
  left: 0;
  right: 0;
  margin-inline: auto;
  width: min(540px, calc(100vw - 40px));
  max-height: 88dvh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--wao-bg-card);
  border-radius: 14px;
  z-index: 101;
  box-shadow: var(--wao-shadow-modal);
  animation: waoPop 0.16s ease;
  padding: 0;
}
.modal-body { min-height: 0; overflow-y: auto; padding: 0 22px 18px; }
.spacer {
  flex: 1;
}
.head {
  display: flex;
  align-items: center;
  flex: none;
  padding: 18px 22px 8px;
}
.title {
  font-size: 16px;
  font-weight: 700;
}
.close {
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
}
.close:hover {
  background: var(--wao-bg-hover-2);
}
.close:disabled { cursor: wait; opacity: .45; }
.close i {
  font-size: 16px;
}
.title-input {
  margin-top: 14px;
  width: 100%;
  height: 40px;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 0 12px;
  font-size: 14px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
}
.title-input:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.label {
  margin-top: 14px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.label-hint {
  margin-left: 6px;
  color: var(--wao-text-faint);
}
.desc-wrap {
  margin-top: 6px;
}
.img-grid {
  margin-top: 8px;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(84px, 1fr));
  gap: 8px;
}
.img-cell {
  position: relative;
  aspect-ratio: 1;
  border: 1px solid var(--wao-line);
  border-radius: 8px;
  overflow: hidden;
  background: var(--wao-divider);
}
.img-cell img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.img-del {
  position: absolute;
  top: 3px;
  right: 3px;
  width: 18px;
  height: 18px;
  border: none;
  border-radius: 5px;
  background: rgba(23, 25, 35, 0.6);
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.img-del i {
  font-size: 12px;
}
.add-img {
  margin-top: 8px;
  height: 30px;
  padding: 0 12px;
  border: 1px dashed var(--wao-line-strong);
  background: none;
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-3);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.add-img:hover {
  border-color: var(--wao-primary);
  color: var(--wao-primary-text);
}
.add-img i {
  font-size: 15px;
}
.pills {
  margin-top: 6px;
  display: flex;
  gap: 7px;
  flex-wrap: wrap;
}
.schedule-switch {
  display: inline-flex;
  margin-top: 6px;
  padding: 2px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
}
.schedule-switch button {
  height: 27px;
  padding: 0 11px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
.schedule-switch button.on { background: var(--wao-primary); color: #fff; }
.range-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 8px; }
.range-fields label { display: grid; gap: 4px; font-size: 11.5px; color: var(--wao-text-weak); }
.range-fields input {
  height: 36px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  padding: 0 8px;
  color: var(--wao-text);
  background: var(--wao-bg-card);
}
.pill {
  height: 28px;
  padding: 0 11px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  border-radius: 999px;
  font-size: 12px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.pill.on {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  border-color: #c7d0f1;
}
.pill .dot {
  width: 6px;
  height: 6px;
  border-radius: 2px;
}
.two-col {
  margin-top: 14px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.seg-row {
  margin-top: 6px;
  display: flex;
  gap: 7px;
}
.seg {
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-3);
  border-radius: 8px;
  font-size: 12.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.seg.on {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  border-color: #c7d0f1;
}
.seg.pr {
  font-size: 12px;
}
.av {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #ccc;
  color: #fff;
  font-size: 9.5px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.foot {
  flex: none;
  margin-top: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 64px;
  padding: 10px 22px;
  border-top: 1px solid var(--wao-divider);
  background: var(--wao-bg-card);
}
.foot .create-error, .foot .draft-state { min-width: 0; overflow-wrap: anywhere; }
.draft-state {
  font-size: 11.5px;
  color: var(--wao-success-strong);
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.create-error {
  color: var(--wao-danger);
  font-size: 11.5px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.spin {
  animation: waoSpin 0.9s linear infinite;
}
@keyframes waoSpin {
  to { transform: rotate(360deg); }
}
.btn-ghost {
  height: 34px;
  padding: 0 14px;
  border: 1px solid var(--wao-line-strong);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}
.btn-ghost:hover {
  background: var(--wao-bg-hover);
}
.btn-primary {
  height: 34px;
  padding: 0 16px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.btn-ghost:disabled,
.btn-primary:disabled { cursor: not-allowed; opacity: .58; }
.btn-primary:hover:not(:disabled) {
  background: var(--wao-primary-hover);
}
/* 手机：改成从底部升起的整页表单。
   居中弹窗在手机上会被键盘顶得只剩一条缝，标题框还常常被推到屏幕外；
   贴底之后，输入框始终在键盘正上方，创建按钮固定在拇指够得到的地方。 */
@media (max-width: 760px) {
  .modal {
    top: auto;
    bottom: 0;
    width: 100%;
    max-width: none;
    max-height: 92dvh;
    border-radius: 16px 16px 0 0;
    padding: 0;
    animation: waoSheet 0.18s ease;
  }
  .head {
    background: var(--wao-bg-card);
    padding: 14px 14px 8px;
  }
  .modal-body { padding: 0 14px 18px; }
  .type-field { flex-basis: 94px; }
  /* 16px 是 iOS Safari 不自动放大页面的下限，比美观重要 */
  .title-input {
    height: 46px;
    font-size: 16px;
  }
  .pill,
  .seg {
    height: 34px;
  }
  .range-fields { grid-template-columns: 1fr; }
  .foot {
    flex-wrap: wrap;
    gap: 8px;
    padding: 10px 14px calc(10px + env(safe-area-inset-bottom));
    background: var(--wao-bg-card);
    border-top: 1px solid var(--wao-divider);
  }
  .foot .spacer { display: none; }
  .draft-state,
  .create-error { width: 100%; }
  .btn-ghost,
  .btn-primary { flex: 1; height: 44px; font-size: 14px; }
}
@keyframes waoSheet {
  from { transform: translateY(24px); opacity: 0.6; }
}
</style>
