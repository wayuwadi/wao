<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { usePreferenceStore } from '@/stores/preferences'
import { priorityMeta } from '@/utils/domain'
import { toast } from '@/composables/toast'
import { nowIso } from '@/utils/time'
// 异步加载，理由同 TaskDrawer：ProseMirror 不进首屏包。
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
import type { Priority } from '@/types'

const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()
const preferences = usePreferenceStore()

/** 快捷截止选项按真实「今天」推算：今天 / 明天 / 本周日 / 下周一；ISO 日期（日历双击带入）原样返回。 */
function dueDate(label: string): string {
  if (/^\d{4}-\d{2}-\d{2}$/.test(label)) return label
  const d = new Date(`${data.today}T00:00:00`)
  const dow = d.getDay() || 7
  if (label === '明天') d.setDate(d.getDate() + 1)
  else if (label === '周日') d.setDate(d.getDate() + (7 - dow))
  else if (label === '下周一') d.setDate(d.getDate() + (8 - dow))
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

const DRAFT_KEY = 'wao-new-task-draft-v1'
type Draft = {
  title: string; project: string; owner: string; pr: Priority; due: string; desc: string
  scheduleMode: 'deadline' | 'range'; startAt: string; endAt: string
  usePdca: boolean; pdcaPlan: string
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
const title = ref(draft.title ?? '')
// modalProject 未预选时是空串（不是 null），必须用 || 兜底到第一个进行中的项目，
// 否则从顶栏全局入口建出的任务 project 为空，在所有项目列表里都看不见。
const project = ref(
  draft.project || ui.modalProject || preferences.defaultProject || openProjects.value[0]?.id || '',
)

/**
 * 真实项目列表到达后，只有当前预选项目不在列表中时才回退到首个可用项目。
 * 这样既能清理 fixtures / 过期草稿留下的伪 id，也不会覆盖用户已经选中的真实项目。
 */
watch(openProjects, (projects) => {
  if (!projects.some((item) => item.id === project.value)) {
    project.value = projects.some((item) => item.id === preferences.defaultProject)
      ? preferences.defaultProject
      : projects[0]?.id || ''
  }
}, { immediate: true })
watch(() => preferences.defaultProject, (value) => {
  if (!draft.project && !ui.modalProject && value && openProjects.value.some((item) => item.id === value)) {
    project.value = value
  }
})
const owner = ref(draft.owner ?? session.currentUser.id)
const pr = ref<Priority>(draft.pr ?? 'P2')
// 日历双击某天进来时，截止日直接锁定那一天，优先于草稿里存的快捷选项。
// 草稿里的 ISO 日期（上次从日历带入的）没有对应 pill，回落到「今天」。
const QUICK_DUES = ['今天', '明天', '周日', '下周一']
const due = ref(ui.modalDue || (QUICK_DUES.includes(draft.due ?? '') ? draft.due! : '今天'))
const dueOptions = computed(() => (ui.modalDue ? [ui.modalDue, ...QUICK_DUES] : QUICK_DUES))
const scheduleMode = ref<'deadline' | 'range'>(
  ui.modalStartAt ? 'range' : draft.scheduleMode === 'range' ? 'range' : 'deadline',
)
const startAt = ref(ui.modalStartAt || draft.startAt || `${dueDate(due.value)}T09:00`)
const endAt = ref(ui.modalEndAt || draft.endAt || `${dueDate(due.value)}T10:00`)
const desc = ref(draft.desc ?? '')
const usePdca = ref(draft.usePdca ?? false)
const pdcaPlan = ref(draft.pdcaPlan ?? '')
const draftState = ref<'saving' | 'saved'>('saved')
let draftTimer: ReturnType<typeof setTimeout> | null = null

watch([title, project, owner, pr, due, desc, scheduleMode, startAt, endAt, usePdca, pdcaPlan], () => {
  draftState.value = 'saving'
  localStorage.setItem(DRAFT_KEY, JSON.stringify({
    title: title.value, project: project.value, owner: owner.value,
    pr: pr.value, due: due.value, desc: desc.value,
    scheduleMode: scheduleMode.value, startAt: startAt.value, endAt: endAt.value,
    usePdca: usePdca.value, pdcaPlan: pdcaPlan.value,
  } satisfies Draft))
  if (draftTimer) clearTimeout(draftTimer)
  draftTimer = setTimeout(() => (draftState.value = 'saved'), 180)
}, { flush: 'sync' })

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

function create() {
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
  if (usePdca.value && !pdcaPlan.value.trim()) {
    toast('请先写下本轮希望达成的结果', 'ri-error-warning-line')
    return
  }
  // addTask 立即返回本地记录、后台落库，所以这里不 await —— 弹窗即点即关。
  void data.addTask({
    title: name, project: project.value, pr: pr.value, owner: owner.value,
    due: scheduleMode.value === 'range' ? startAt.value.slice(0, 10) : dueDate(due.value),
    startAt: scheduleMode.value === 'range' ? startAt.value : null,
    endAt: scheduleMode.value === 'range' ? endAt.value : null,
    desc: desc.value.trim(), images: [],
    pdcaCycles: usePdca.value ? [{
      id: `pc${Date.now()}`,
      plan: pdcaPlan.value.trim(), check: '', act: '', outcome: null,
      startedAt: nowIso(), reviewedAt: null,
    }] : [],
  })
  localStorage.removeItem(DRAFT_KEY)
  ui.closeModal()
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
</script>

<template>
  <div class="mask" @click="ui.closeModal()"></div>
  <div class="modal task">
    <div class="head">
      <div class="title">新建任务</div>
      <div class="spacer"></div>
      <button class="close" @click="ui.closeModal()"><i class="ri-close-line"></i></button>
    </div>
    <input
      ref="titleInput"
      v-model="title"
      class="title-input"
      enterkeyhint="done"
      placeholder="任务标题，例如「整理证件盒 · 第 4 批」"
      @keydown.enter="create"
    />

    <div class="label">所属项目</div>
    <div class="pills">
      <button
        v-for="p in openProjects"
        :key="p.id"
        class="pill"
        :class="{ on: project === p.id }"
        @click="project = p.id"
      >
        <span class="dot" :style="{ background: data.areaColor(p.area) }"></span>{{ p.name }}
      </button>
      <button
        v-if="project"
        class="pill default-project"
        :class="{ on: preferences.defaultProject === project }"
        type="button"
        @click="makeDefaultProject"
      >
        <i class="ri-pushpin-line"></i>{{ preferences.defaultProject === project ? '默认项目' : '设为默认' }}
      </button>
    </div>

    <div class="two-col">
      <div>
        <div class="label">负责人</div>
        <div class="seg-row">
          <button v-for="m in session.members" :key="m.id" class="seg" :class="{ on: owner === m.id }" @click="owner = m.id">
            <span class="av" :style="{ background: m.color }">{{ m.initial }}</span>{{ m.name }}
          </button>
        </div>
      </div>
      <div>
        <div class="label">优先级</div>
        <div class="seg-row">
          <button
            v-for="p in (['P0', 'P1', 'P2', 'P3'] as Priority[])"
            :key="p"
            class="seg pr"
            :style="pr === p ? { background: priorityMeta(p).bg, color: priorityMeta(p).color, borderColor: priorityMeta(p).color } : {}"
            @click="pr = p"
          >
            {{ p }}
          </button>
        </div>
      </div>
    </div>

    <div class="label">时间安排</div>
    <div class="schedule-switch">
      <button :class="{ on: scheduleMode === 'deadline' }" @click="scheduleMode = 'deadline'">截止日期</button>
      <button :class="{ on: scheduleMode === 'range' }" @click="scheduleMode = 'range'">时间段</button>
    </div>
    <div v-if="scheduleMode === 'deadline'" class="pills">
      <button v-for="d in dueOptions" :key="d" class="pill sq" :class="{ on: due === d }" @click="due = d">{{ d }}</button>
    </div>
    <div v-else class="range-fields">
      <label><span>开始</span><input v-model="startAt" type="datetime-local" /></label>
      <label><span>结束</span><input v-model="endAt" type="datetime-local" /></label>
    </div>

    <div class="label">描述 <span class="label-hint">敲 ## 变标题、- 变列表；粘贴或拖入的截图会插在光标处</span></div>
    <div class="desc-wrap">
      <MarkdownEditor
        v-model="desc"
        placeholder="补充背景、目标与边界…可直接 Ctrl/Cmd+V 粘贴截图"
        :min-height="110"
        @upload-image="onUploadImage"
      />
    </div>

    <div class="pdca-option" :class="{ on: usePdca }">
      <button class="pdca-toggle" type="button" role="switch" :aria-checked="usePdca" @click="usePdca = !usePdca">
        <span class="switch-dot"></span>
      </button>
      <button class="pdca-copy" type="button" @click="usePdca = !usePdca">
        <b>使用 PDCA 跟踪</b>
        <span>完成后记录结果和改进，并可从改进行动开启下一轮</span>
      </button>
    </div>
    <label v-if="usePdca" class="pdca-plan">
      <span>P · 本轮计划与判定标准</span>
      <textarea v-model="pdcaPlan" rows="3" placeholder="例如：连续 7 天在 23:30 前睡觉；至少达成 5 天即算本轮有效"></textarea>
    </label>

    <div class="foot">
      <span class="draft-state"><i :class="draftState === 'saving' ? 'ri-loader-4-line spin' : 'ri-checkbox-circle-line'"></i>{{ draftState === 'saving' ? '正在保存草稿' : '草稿已保存' }}</span>
      <div class="spacer"></div>
      <button class="btn-ghost" @click="ui.closeModal()">取消</button>
      <button class="btn-primary" :disabled="!title.trim()" @click="create">创建任务</button>
    </div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(23, 25, 35, 0.32);
  z-index: 100;
  animation: waoFade 0.15s ease;
}
.modal {
  position: fixed;
  top: 12vh;
  /* 用 margin 居中而不是 transform：waoPop 动画也动 transform，
     两者叠在一起时居中位移会被覆盖，卡片先偏右出现再跳回中间。 */
  left: 0;
  right: 0;
  margin-inline: auto;
  width: min(540px, calc(100vw - 40px));
  max-height: 76vh;
  overflow-y: auto;
  background: var(--wao-bg-card);
  border-radius: 14px;
  z-index: 101;
  box-shadow: var(--wao-shadow-modal);
  animation: waoPop 0.16s ease;
  padding: 20px 22px;
}
.spacer {
  flex: 1;
}
.head {
  display: flex;
  align-items: center;
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
  color: var(--wao-primary);
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
  margin-top: 20px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.draft-state {
  font-size: 11.5px;
  color: var(--wao-success-strong);
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
}
.btn-primary:hover:not(:disabled) {
  background: var(--wao-primary-hover);
}
.pdca-option {
  margin-top: 14px;
  padding: 10px 12px;
  border: 1px solid var(--wao-line);
  border-radius: 10px;
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--wao-bg-sub);
}
.pdca-option.on {
  border-color: #bfcaf4;
  background: var(--wao-primary-soft);
}
.pdca-toggle {
  width: 34px;
  height: 20px;
  padding: 2px;
  border: none;
  border-radius: 999px;
  background: var(--wao-line-strong);
  cursor: pointer;
  flex-shrink: 0;
}
.pdca-option.on .pdca-toggle { background: var(--wao-primary); }
.switch-dot {
  display: block;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  transition: transform .16s ease;
}
.pdca-option.on .switch-dot { transform: translateX(14px); }
.pdca-copy {
  padding: 0;
  border: none;
  background: none;
  text-align: left;
  color: var(--wao-text);
  cursor: pointer;
  display: grid;
  gap: 2px;
}
.pdca-copy b { font-size: 12.5px; }
.pdca-copy span { color: var(--wao-text-weak); font-size: 11.5px; }
.pdca-plan {
  display: grid;
  gap: 6px;
  margin-top: 10px;
  color: var(--wao-primary-text);
  font-size: 12px;
  font-weight: 600;
}
.pdca-plan textarea {
  width: 100%;
  box-sizing: border-box;
  resize: vertical;
  border: 1px solid #c7d0f1;
  border-radius: 9px;
  padding: 9px 10px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  font-family: inherit;
  font-size: 13px;
  font-weight: 400;
  line-height: 1.6;
  outline: none;
}
.pdca-plan textarea:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, .1);
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
    padding: 14px 14px calc(10px + env(safe-area-inset-bottom));
    animation: waoSheet 0.18s ease;
  }
  .head {
    position: sticky;
    top: -14px;
    z-index: 2;
    background: var(--wao-bg-card);
    padding: 4px 0 6px;
  }
  /* 16px 是 iOS Safari 不自动放大页面的下限，比美观重要 */
  .title-input {
    height: 46px;
    font-size: 16px;
  }
  .pill,
  .seg {
    height: 34px;
  }
  .two-col { grid-template-columns: 1fr; gap: 10px; }
  .range-fields { grid-template-columns: 1fr; }
  .foot {
    position: sticky;
    bottom: calc(-10px - env(safe-area-inset-bottom));
    z-index: 2;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 16px;
    padding: 10px 0 calc(6px + env(safe-area-inset-bottom));
    background: var(--wao-bg-card);
    border-top: 1px solid var(--wao-divider);
  }
  .foot .spacer { display: none; }
  .draft-state { width: 100%; }
  .btn-ghost,
  .btn-primary { flex: 1; height: 44px; font-size: 14px; }
}
@keyframes waoSheet {
  from { transform: translateY(24px); opacity: 0.6; }
}
</style>
