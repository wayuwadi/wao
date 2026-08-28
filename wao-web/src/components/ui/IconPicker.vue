<script setup lang="ts">
import { computed, ref } from 'vue'

const model = defineModel<string>({ required: true })
const emit = defineEmits<{ select: [icon: string] }>()
const open = ref(false)
const query = ref('')

const ICONS = [
  'ri-home-5-line', 'ri-compass-discover-line', 'ri-stack-line', 'ri-folder-3-line',
  'ri-checkbox-multiple-line', 'ri-calendar-2-line', 'ri-timer-flash-line', 'ri-book-2-line',
  'ri-file-text-line', 'ri-hard-drive-2-line', 'ri-links-line', 'ri-flashlight-line',
  'ri-sparkling-2-line', 'ri-robot-2-line', 'ri-brain-line', 'ri-chat-3-line',
  'ri-chat-history-line', 'ri-notification-3-line', 'ri-settings-3-line', 'ri-user-3-line',
  'ri-team-line', 'ri-community-line', 'ri-apps-2-line', 'ri-layout-grid-line',
  'ri-dashboard-line', 'ri-bar-chart-box-line', 'ri-pulse-line', 'ri-search-line',
  'ri-draft-line', 'ri-lightbulb-flash-line', 'ri-seedling-line', 'ri-footprint-line',
  'ri-route-line', 'ri-gamepad-line', 'ri-music-2-line', 'ri-image-line',
  'ri-video-line', 'ri-camera-line', 'ri-key-2-line', 'ri-lock-line',
  'ri-server-line', 'ri-computer-line', 'ri-cloud-line', 'ri-git-repository-line',
  'ri-git-branch-line', 'ri-git-pull-request-line', 'ri-code-box-line', 'ri-terminal-box-line',
  'ri-tools-line', 'ri-hammer-line', 'ri-rocket-2-line', 'ri-send-plane-line',
  'ri-map-pin-line', 'ri-global-line', 'ri-customer-service-2-line', 'ri-bear-smile-line',
]

const filtered = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return needle ? ICONS.filter((icon) => icon.includes(needle)) : ICONS
})

function choose(icon: string) {
  model.value = icon
  open.value = false
  query.value = ''
  emit('select', icon)
}
</script>

<template>
  <div class="icon-picker">
    <button class="icon-trigger" type="button" :title="`选择图标（当前 ${model}）`" @click="open = !open">
      <i :class="model || 'ri-apps-line'"></i>
    </button>
    <div v-if="open" class="icon-popover" @keydown.esc="open = false">
      <label><i class="ri-search-line"></i><input v-model="query" autofocus placeholder="搜索图标名称" /></label>
      <div class="icon-grid">
        <button
          v-for="icon in filtered"
          :key="icon"
          type="button"
          :class="{ on: icon === model }"
          :title="icon"
          @click="choose(icon)"
        ><i :class="icon"></i></button>
      </div>
      <p v-if="!filtered.length">没有匹配图标，请换个关键词。</p>
    </div>
  </div>
</template>

<style scoped>
.icon-picker { position: relative; width: 30px; height: 30px; }
.icon-trigger { width: 30px; height: 30px; padding: 0; border: 0; border-radius: 8px; background: var(--wao-bg-sub); color: var(--wao-text-3); cursor: pointer; }
.icon-trigger:hover { color: var(--wao-primary); box-shadow: 0 0 0 2px var(--wao-primary-soft); }
.icon-trigger i { font-size: 16px; }
.icon-popover { position: absolute; z-index: 30; top: 36px; left: 0; width: 280px; padding: 9px; border: 1px solid var(--wao-line); border-radius: 10px; background: var(--wao-bg-card); box-shadow: var(--wao-shadow-pop-2); }
.icon-popover label { height: 32px; padding: 0 8px; border: 1px solid var(--wao-line-control); border-radius: 7px; display: flex; align-items: center; gap: 6px; }
.icon-popover label i { color: var(--wao-text-faint); }.icon-popover input { min-width: 0; flex: 1; border: 0; outline: 0; background: transparent; color: var(--wao-text); font-size: 11px; }
.icon-grid { max-height: 210px; margin-top: 8px; overflow-y: auto; display: grid; grid-template-columns: repeat(7, 1fr); gap: 4px; }
.icon-grid button { aspect-ratio: 1; padding: 0; border: 1px solid transparent; border-radius: 7px; background: var(--wao-bg-sub); color: var(--wao-text-3); cursor: pointer; }.icon-grid button:hover,.icon-grid button.on { border-color: var(--wao-primary); color: var(--wao-primary); background: var(--wao-primary-soft); }.icon-grid i { font-size: 17px; }
.icon-popover p { margin: 10px 0 2px; color: var(--wao-text-weak); text-align: center; font-size: 11px; }
@media (max-width: 760px) { .icon-popover { position: fixed; top: 20%; left: 5vw; width: 90vw; box-sizing: border-box; } }
</style>
