<script setup lang="ts">
import { ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { toast } from '@/composables/toast'
import type { Project } from '@/types'

const props = defineProps<{ project: Project }>()
const emit = defineEmits<{ close: []; deleted: [] }>()

const data = useDataStore()

const name = ref(props.project.name)
const area = ref(props.project.area)
const goal = ref(props.project.goal === '尚未填写目标——打开项目后可补充。' ? '' : props.project.goal)
const icon = ref(props.project.icon || 'ri-stack-line')
const confirming = ref(false)

/** 可选项目图标（remixicon）。 */
const ICONS = [
  'ri-stack-line', 'ri-rocket-line', 'ri-plant-line', 'ri-home-heart-line',
  'ri-briefcase-line', 'ri-book-2-line', 'ri-heart-pulse-line', 'ri-palette-line',
  'ri-code-box-line', 'ri-camera-line', 'ri-flight-takeoff-line', 'ri-cup-line',
  'ri-money-cny-circle-line', 'ri-run-line', 'ri-music-2-line', 'ri-lightbulb-line',
]

async function save() {
  if (!name.value.trim()) return
  await data.updateProject(props.project.id, {
    name: name.value.trim(),
    area: area.value,
    goal: goal.value.trim(),
    icon: icon.value,
  })
  toast('项目设置已保存', 'ri-check-line')
  emit('close')
}

async function remove() {
  await data.deleteProject(props.project.id)
  emit('deleted')
}
</script>

<template>
  <div class="mask" @click="emit('close')"></div>
  <div class="modal">
    <div class="head">
      <div class="title">项目设置</div>
      <span class="head-tip">自定义图标、名称、描述，或删除项目</span>
      <div class="spacer"></div>
      <button class="close" @click="emit('close')"><i class="ri-close-line"></i></button>
    </div>

    <div class="label">项目图标</div>
    <div class="icon-grid">
      <button
        v-for="ic in ICONS"
        :key="ic"
        class="icon-cell"
        :class="{ on: icon === ic }"
        :style="icon === ic ? { background: data.areaColor(area) } : {}"
        @click="icon = ic"
      >
        <i :class="ic"></i>
      </button>
    </div>

    <div class="label">项目名称</div>
    <input v-model="name" class="title-input" placeholder="项目名称" @keydown.enter="save" />

    <div class="label">所属领域</div>
    <div class="pills">
      <button v-for="a in data.areas" :key="a.id" class="pill" :class="{ on: area === a.id }" @click="area = a.id">
        <span class="dot" :style="{ background: a.color }"></span>{{ a.name }}
      </button>
    </div>

    <div class="label">项目描述 / 目标</div>
    <textarea v-model="goal" class="goal" rows="3" placeholder="这个项目做成了，会是什么样？"></textarea>

    <div class="danger-zone">
      <template v-if="!confirming">
        <div class="dz-text">
          <div class="dz-title">删除项目</div>
          <div class="dz-sub">将同时移除该项目下的任务与关联知识库，不可恢复。</div>
        </div>
        <button class="btn-danger" @click="confirming = true"><i class="ri-delete-bin-line"></i>删除</button>
      </template>
      <template v-else>
        <div class="dz-text">
          <div class="dz-title">确认删除「{{ project.name }}」？</div>
          <div class="dz-sub">此操作不可撤销。</div>
        </div>
        <button class="btn-ghost sm" @click="confirming = false">取消</button>
        <button class="btn-danger" @click="remove"><i class="ri-delete-bin-line"></i>确认删除</button>
      </template>
    </div>

    <div class="foot">
      <div class="spacer"></div>
      <button class="btn-ghost" @click="emit('close')">取消</button>
      <button class="btn-primary" :disabled="!name.trim()" @click="save">保存</button>
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
  top: 8vh;
  left: 50%;
  transform: translateX(-50%);
  width: min(560px, calc(100vw - 40px));
  max-height: 84vh;
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
  gap: 10px;
}
.title {
  font-size: 16px;
  font-weight: 700;
}
.head-tip {
  font-size: 12px;
  color: var(--wao-text-weak);
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
.label {
  margin-top: 14px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.icon-grid {
  margin-top: 8px;
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 8px;
}
.icon-cell {
  aspect-ratio: 1;
  border: 1px solid var(--wao-line-control);
  background: #fff;
  border-radius: 9px;
  color: var(--wao-text-3);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}
.icon-cell i {
  font-size: 17px;
}
.icon-cell:hover {
  border-color: #c7d0f1;
}
.icon-cell.on {
  color: #fff;
  border-color: transparent;
}
.title-input {
  margin-top: 6px;
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
.goal {
  margin-top: 6px;
  width: 100%;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.7;
  background: #fff;
  color: var(--wao-text);
  outline: none;
  resize: none;
}
.goal:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.danger-zone {
  margin-top: 18px;
  border: 1px solid #f0d9d9;
  background: #fdf7f7;
  border-radius: 10px;
  padding: 12px 14px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.dz-text {
  flex: 1;
  min-width: 0;
}
.dz-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--wao-danger-strong);
}
.dz-sub {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.btn-danger {
  height: 32px;
  padding: 0 12px;
  border: none;
  background: var(--wao-danger);
  color: #fff;
  border-radius: 8px;
  font-size: 12.5px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.btn-danger:hover {
  filter: brightness(0.95);
}
.btn-danger i {
  font-size: 14px;
}
.foot {
  margin-top: 20px;
  display: flex;
  align-items: center;
  gap: 10px;
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
.btn-ghost.sm {
  height: 32px;
  padding: 0 12px;
  font-size: 12.5px;
  flex-shrink: 0;
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
.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
