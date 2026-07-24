<script setup lang="ts">
import { ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { useSessionStore } from '@/stores/session'
import { useUiStore } from '@/stores/ui'
import { priorityMeta } from '@/utils/domain'
import { toast } from '@/composables/toast'
import type { Priority } from '@/types'

const data = useDataStore()
const session = useSessionStore()
const ui = useUiStore()

const DUE_MAP: Record<string, string> = { 今天: '2026-07-23', 明天: '2026-07-24', 周日: '2026-07-26', 下周一: '2026-07-27' }

const title = ref('')
const project = ref(ui.modalProject || 'p1')
const owner = ref(session.currentUser.id)
const pr = ref<Priority>('P2')
const due = ref('今天')

const openProjects = data.projects.filter((p) => p.status !== '已完成')

function create() {
  if (!title.value.trim()) return
  const t = data.addTask({
    title: title.value.trim(), project: project.value, pr: pr.value, owner: owner.value,
    due: DUE_MAP[due.value] ?? data.today,
  })
  ui.closeModal()
  toast(`任务「${t.title}」已创建`, 'ri-checkbox-circle-fill')
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

    <div class="foot">
      <span class="foot-tip">创建成功会有 1.5 秒庆祝动画，Esc 可跳过</span>
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
  left: 50%;
  transform: translateX(-50%);
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
.foot-tip {
  font-size: 11.5px;
  color: var(--wao-text-faint);
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
</style>

