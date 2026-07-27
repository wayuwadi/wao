<script setup lang="ts">
import { defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { priorityMeta } from '@/utils/domain'
import { toast } from '@/composables/toast'
// 异步加载，理由同 TaskDrawer：ProseMirror 不进首屏包。
const MarkdownEditor = defineAsyncComponent(() => import('@/components/ui/MarkdownEditor.vue'))
import type { Priority } from '@/types'

const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()

/** 快捷截止选项按真实「今天」推算：今天 / 明天 / 本周日 / 下周一。 */
function dueDate(label: string): string {
  const d = new Date(`${data.today}T00:00:00`)
  const dow = d.getDay() || 7
  if (label === '明天') d.setDate(d.getDate() + 1)
  else if (label === '周日') d.setDate(d.getDate() + (7 - dow))
  else if (label === '下周一') d.setDate(d.getDate() + (8 - dow))
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

const DRAFT_KEY = 'wao-new-task-draft-v1'
type Draft = { title: string; project: string; owner: string; pr: Priority; due: string; desc: string }
function loadDraft(): Partial<Draft> {
  try { return JSON.parse(localStorage.getItem(DRAFT_KEY) || '{}') } catch { return {} }
}
const draft = loadDraft()
const title = ref(draft.title ?? '')
const project = ref(draft.project ?? ui.modalProject ?? 'p1')
const owner = ref(draft.owner ?? session.currentUser.id)
const pr = ref<Priority>(draft.pr ?? 'P2')
const due = ref(draft.due ?? '今天')
const desc = ref(draft.desc ?? '')
const draftState = ref<'saving' | 'saved'>('saved')
let draftTimer: ReturnType<typeof setTimeout> | null = null

watch([title, project, owner, pr, due, desc], () => {
  draftState.value = 'saving'
  localStorage.setItem(DRAFT_KEY, JSON.stringify({
    title: title.value, project: project.value, owner: owner.value,
    pr: pr.value, due: due.value, desc: desc.value,
  } satisfies Draft))
  if (draftTimer) clearTimeout(draftTimer)
  draftTimer = setTimeout(() => (draftState.value = 'saved'), 180)
}, { flush: 'sync' })

onBeforeUnmount(() => { if (draftTimer) clearTimeout(draftTimer) })

const openProjects = data.projects.filter((p) => p.status !== '已完成')

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
  // addTask 立即返回本地记录、后台落库，所以这里不 await —— 弹窗即点即关。
  void data.addTask({
    title: name, project: project.value, pr: pr.value, owner: owner.value,
    due: dueDate(due.value),
    desc: desc.value.trim(), images: [],
  })
  localStorage.removeItem(DRAFT_KEY)
  ui.closeModal()
  toast(`任务「${name}」已创建`, 'ri-checkbox-circle-fill')
  ui.celebrate()
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
    <input v-model="title" class="title-input" placeholder="任务标题，例如「整理证件盒 · 第 4 批」" @keydown.enter="create" />

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

    <div class="label">截止</div>
    <div class="pills">
      <button v-for="d in ['今天', '明天', '周日', '下周一']" :key="d" class="pill sq" :class="{ on: due === d }" @click="due = d">{{ d }}</button>
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
  background: #fff;
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
  background: #fff;
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
.pill {
  height: 28px;
  padding: 0 11px;
  border: 1px solid var(--wao-line-control);
  background: #fff;
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
  background: #fff;
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
  background: #fff;
  color: var(--wao-text-2);
  border-radius: 8px;
  font-size: 13px;
  cursor: pointer;
}
.btn-ghost:hover {
  background: #f4f5f8;
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

@media (max-width: 760px) {
  .modal { top: 12px; width: calc(100vw - 24px); max-height: calc(100dvh - 24px); }
  .modal-head, .modal-body, .modal-foot { padding-left: 14px; padding-right: 14px; }
  .two-col { grid-template-columns: 1fr; gap: 10px; }
  .foot { flex-wrap: wrap; }
  .foot .spacer { display: none; }
}
</style>
