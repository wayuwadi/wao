<script setup lang="ts">
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { toast } from '@/composables/toast'

const data = useDataStore()
const ui = useUiStore()
const router = useRouter()

const name = ref('')
const area = ref('career')
const goal = ref('')
const vis = ref('仅自己可见')

const VIS_OPTS = [
  { label: '仅自己可见', desc: '创建后再邀请成员', icon: 'ri-lock-line' },
  { label: '全部成员可见', desc: '组织内成员可查看', icon: 'ri-team-line' },
]

const saving = ref(false), error = ref('')
async function create() {
  if (!name.value.trim() || saving.value) return
  saving.value = true; error.value = ''
  try {
  const p = await data.addProject({
    name: name.value.trim(), area: area.value, goal: goal.value.trim(), visAll: vis.value === '全部成员可见',
  })
  ui.closeModal()
  toast(`项目「${p.name}」已创建`, 'ri-checkbox-circle-fill')
  ui.celebrate()
  router.push(`/projects/${p.id}`)
  } catch { error.value = '保存失败，输入已保留，请重试。' }
  finally { saving.value = false }
}
async function requestClose() {
  if (saving.value) return
  if (name.value.trim()) await create()
  else if (goal.value.trim() || area.value !== 'career' || vis.value !== '仅自己可见') error.value = '请填写项目名称后保存，输入已保留。'
  else ui.closeModal()
}
useOverlayDismiss(() => true, requestClose)
</script>

<template>
  <div class="mask" @click="requestClose"></div>
  <div class="modal" :inert="saving"><p v-if="error" role="alert">{{ error }}</p>
    <div class="head">
      <div class="title">新建项目</div>
      <span class="head-tip">软件项目或人生大事，都值得立项</span>
      <div class="spacer"></div>
      <button class="close" @click="requestClose"><i class="ri-close-line"></i></button>
    </div>
    <input v-model="name" class="title-input" placeholder="项目名称，例如「阳台花园改造」" @keydown.enter="create" />

    <div class="label">所属领域</div>
    <div class="pills">
      <button v-for="a in data.areas" :key="a.id" class="pill" :class="{ on: area === a.id }" @click="area = a.id">
        <span class="dot" :style="{ background: a.color }"></span>{{ a.name }}
      </button>
    </div>

    <div class="label">一句话目标</div>
    <textarea v-model="goal" class="goal" rows="2" placeholder="这个项目做成了，会是什么样？"></textarea>

    <div class="label">可见范围 · 新项目默认仅创建者可见</div>
    <div class="vis-grid">
      <button v-for="v in VIS_OPTS" :key="v.label" class="vis" :class="{ on: vis === v.label }" @click="vis = v.label">
        <div class="vis-label"><i :class="v.icon"></i>{{ v.label }}</div>
        <div class="vis-desc">{{ v.desc }}</div>
      </button>
    </div>

    <div class="foot">
      <span class="foot-tip">创建后可随时邀请成员并设置查看 / 编辑权限</span>
      <div class="spacer"></div>
      <button class="btn-ghost" @click="requestClose">完成并关闭</button>
      <button class="btn-primary" :disabled="!name.trim()" @click="create">创建项目</button>
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
  top: 10vh;
  /* 用 margin 居中而不是 transform：waoPop 动画也动 transform，
     两者叠在一起时居中位移会被覆盖，卡片先偏右出现再跳回中间。 */
  left: 0;
  right: 0;
  margin-inline: auto;
  width: min(560px, calc(100vw - 40px));
  max-height: 80vh;
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
.goal {
  margin-top: 6px;
  width: 100%;
  border: 1px solid var(--wao-line-control);
  border-radius: 9px;
  padding: 8px 12px;
  font-size: 13px;
  line-height: 1.7;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  outline: none;
  resize: none;
}
.goal:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.vis-grid {
  margin-top: 6px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.vis {
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  border-radius: 10px;
  padding: 11px 12px;
  cursor: pointer;
  text-align: left;
}
.vis.on {
  border-color: var(--wao-primary);
  background: var(--wao-primary-tint);
}
.vis-label {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
  color: var(--wao-text);
}
.vis-label i {
  font-size: 14px;
  color: var(--wao-text-3);
}
.vis-desc {
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
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

@media (max-width: 760px) {
  .modal { top: 12px; width: calc(100vw - 24px); max-height: calc(100dvh - 24px); }
  .modal-head, .modal-body, .modal-foot { padding-left: 14px; padding-right: 14px; }
  .two-col { grid-template-columns: 1fr; }
}
</style>
