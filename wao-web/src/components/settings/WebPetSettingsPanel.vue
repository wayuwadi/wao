<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { toast } from '@/composables/toast'
import {
  type WebPetSettings,
  type WebPetTriggerAction,
  useWebPetStore,
} from '@/stores/webPet'

const pet = useWebPetStore()
const draft = ref<WebPetSettings | null>(null)
const baseline = ref('')

const triggerOptions: Array<{ value: WebPetTriggerAction; label: string; detail: string }> = [
  { value: 'QUICK_ACTIONS', label: '快捷操作气泡', detail: '展示今日节奏快捷记录' },
  { value: 'SETTINGS', label: '宠物功能气泡', detail: '展示设置、气泡静音和隐藏宠物' },
  { value: 'NONE', label: '不响应', detail: '保留宠物但不展开菜单' },
]

const dirty = computed(() => !!draft.value && JSON.stringify(draft.value) !== baseline.value)

watch(
  () => pet.settings,
  (value) => {
    if (dirty.value || pet.saveState === 'saving') return
    draft.value = { ...value }
    baseline.value = JSON.stringify(value)
  },
  { deep: true },
)

async function load(force = false) {
  await pet.load(force)
  if (pet.loadState === 'error' && !draft.value) return
  draft.value = { ...pet.settings }
  baseline.value = JSON.stringify(pet.settings)
}

async function save() {
  if (!draft.value || !dirty.value || pet.saveState === 'saving') return
  try {
    const saved = await pet.save(draft.value)
    draft.value = { ...saved }
    baseline.value = JSON.stringify(saved)
    toast('宠物设置已保存并立即生效', 'ri-bear-smile-line')
  } catch {
    // 原草稿保留在表单中，页面直接提供重试。
  }
}

function confirmLeave() {
  return !dirty.value || window.confirm('宠物设置还有未保存的修改，确认放弃吗？')
}

defineExpose({ confirmLeave })
onMounted(() => void load())
</script>

<template>
  <div class="settings-heading">
    <div>
      <h1>网页宠物设置</h1>
      <p>按当前成员跨设备保存；临时静音只影响当前浏览器，并可随时从侧边小图标恢复。</p>
    </div>
    <button
      class="primary"
      type="button"
      :disabled="!dirty || pet.saveState === 'saving'"
      @click="save"
    >
      {{ pet.saveState === 'saving' ? '保存中…' : dirty ? '保存宠物设置' : '已保存' }}
    </button>
  </div>

  <div v-if="pet.loadState === 'loading' && !draft" class="state-card">
    <i class="ri-loader-4-line spin"></i>正在读取宠物设置…
  </div>
  <div v-else-if="pet.loadState === 'error' && !draft" class="state-card error" role="alert">
    <span>{{ pet.error }}</span><button type="button" @click="load(true)">重新读取</button>
  </div>
  <form v-else-if="draft" class="pet-settings" @submit.prevent="save">
    <section class="settings-card">
      <header><div><h2>触发方式</h2><p>分别决定悬停、左键和右键时出现哪一种气泡。</p></div></header>
      <div class="trigger-grid">
        <label>
          <span>鼠标悬停</span>
          <select v-model="draft.hoverAction" aria-label="宠物悬停动作">
            <option v-for="option in triggerOptions" :key="option.value" :value="option.value">
              {{ option.label }} · {{ option.detail }}
            </option>
          </select>
        </label>
        <label>
          <span>左键点击</span>
          <select v-model="draft.leftClickAction" aria-label="宠物左键动作">
            <option v-for="option in triggerOptions" :key="option.value" :value="option.value">
              {{ option.label }} · {{ option.detail }}
            </option>
          </select>
        </label>
        <label>
          <span>右键点击</span>
          <select v-model="draft.rightClickAction" aria-label="宠物右键动作">
            <option v-for="option in triggerOptions" :key="option.value" :value="option.value">
              {{ option.label }} · {{ option.detail }}
            </option>
          </select>
        </label>
      </div>
    </section>

    <section class="settings-card">
      <header><div><h2>气泡与静默</h2><p>关闭气泡或隐藏宠物时使用这些自选时长，右键菜单仍提供 5 分钟和 10 分钟快捷项。</p></div></header>
      <div class="duration-grid">
        <label><span>气泡默认静音</span><div><input v-model.number="draft.noticeSnoozeMinutes" type="number" min="1" max="120" /><em>分钟</em></div></label>
        <label><span>宠物默认隐藏</span><div><input v-model.number="draft.petSnoozeMinutes" type="number" min="1" max="120" /><em>分钟</em></div></label>
        <label><span>任务气泡轮播</span><div><input v-model.number="draft.noticeRotationSeconds" type="number" min="3" max="60" /><em>秒</em></div></label>
      </div>
      <div class="switch-list">
        <label><input v-model="draft.showTaskNotices" type="checkbox" /><span><b>展示真实任务气泡</b><small>关闭后仅保留宠物与手动快捷操作，不生成演示提醒。</small></span></label>
        <label><input v-model="draft.idleAnimation" type="checkbox" /><span><b>空闲基础动作</b><small>关闭后仍保留点击反馈；系统“减少动态效果”始终优先。</small></span></label>
      </div>
    </section>

    <div v-if="pet.saveState === 'error'" class="state-card error" role="alert">
      <span>{{ pet.error }}；未保存草稿仍在，可直接重试。</span>
      <button type="submit">重试保存</button>
    </div>
  </form>
</template>

<style scoped>
.settings-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.settings-heading h1 { margin: 0; font-size: 18px; }
.settings-heading p, .settings-card p { margin: 5px 0 0; color: var(--wao-text-weak); font-size: 12px; line-height: 1.55; }
.primary { min-height: 34px; padding: 0 13px; border: 0; border-radius: 8px; background: var(--wao-primary); color: #fff; cursor: pointer; white-space: nowrap; }
.primary:disabled { opacity: .55; cursor: default; }
.pet-settings { display: grid; gap: 14px; margin-top: 14px; }
.settings-card { padding: 16px; border: 1px solid var(--wao-line); border-radius: 12px; background: var(--wao-bg-card); }
.settings-card h2 { margin: 0; font-size: 14px; }
.trigger-grid, .duration-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-top: 15px; }
.trigger-grid label, .duration-grid label { display: grid; gap: 6px; color: var(--wao-text-weak); font-size: 11px; }
select, input[type='number'] { width: 100%; height: 36px; box-sizing: border-box; padding: 0 9px; border: 1px solid var(--wao-line-control); border-radius: 8px; background: var(--wao-bg-card); color: var(--wao-text); }
.duration-grid label > div { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 7px; }
.duration-grid em { font-style: normal; }
.switch-list { display: grid; gap: 9px; margin-top: 15px; }
.switch-list label { display: flex; align-items: flex-start; gap: 9px; padding: 10px; border: 1px solid var(--wao-divider); border-radius: 9px; cursor: pointer; }
.switch-list input { margin-top: 2px; }
.switch-list span, .switch-list b, .switch-list small { display: block; }
.switch-list b { color: var(--wao-text); font-size: 12px; }
.switch-list small { margin-top: 3px; color: var(--wao-text-weak); font-size: 10.5px; line-height: 1.45; }
.state-card { display: flex; align-items: center; gap: 8px; margin-top: 14px; padding: 12px; border-radius: 9px; background: var(--wao-bg-sub); color: var(--wao-text-weak); font-size: 12px; }
.state-card span { flex: 1; }.state-card button { border: 0; background: none; color: inherit; cursor: pointer; }
.state-card.error { background: var(--wao-danger-bg); color: var(--wao-danger-strong); }
.spin { display: inline-block; animation: spin .9s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) { .settings-heading { flex-direction: column; }.primary { width: 100%; }.trigger-grid, .duration-grid { grid-template-columns: 1fr; } }
</style>
