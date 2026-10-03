<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { watch } from 'vue'
import NotificationCenter from '@/components/notifications/NotificationCenter.vue'
import VersionUpdateNotice from '@/components/ui/VersionUpdateNotice.vue'
import { useTheme } from '@/composables/theme'
import { useUiStore } from '@/stores/ui'
import { useOverlayDismiss } from '@/composables/overlayDismiss'

const theme = useTheme()
const ui = useUiStore()
const route = useRoute()
const open = ref(false)
const unread = ref(false)
const updateAvailable = ref(false)
const updateArriving = ref(false)
const trigger = ref<HTMLButtonElement>()
const panel = ref<HTMLElement>()
const position = ref({ right: '12px', top: '60px' })
const themeLabel = computed(() => ({ light: '浅色', dark: '深色', system: '跟随系统' })[theme.mode.value])
function place() {
  const rect = trigger.value?.getBoundingClientRect()
  if (!rect) return
  position.value = { right: `${Math.max(12, window.innerWidth - rect.right)}px`, top: `${Math.max(12, Math.min(rect.bottom + 8, window.innerHeight - 310))}px` }
}
async function toggle() {
  place()
  open.value = !open.value
  if (open.value) { await nextTick(); panel.value?.querySelector<HTMLButtonElement>('button')?.focus() }
}
function close() { open.value = false; trigger.value?.focus() }
function outside(event: PointerEvent) {
  if (event.target instanceof Node && !panel.value?.contains(event.target) && !trigger.value?.contains(event.target)) open.value = false
}
function shortcuts() { open.value = false; ui.toggleHelp() }
useOverlayDismiss(() => open.value, close)
watch(() => route.fullPath, () => { open.value = false })
onMounted(() => { document.addEventListener('pointerdown', outside); window.addEventListener('resize', place); window.addEventListener('scroll', place, true) })
onBeforeUnmount(() => { document.removeEventListener('pointerdown', outside); window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true) })
</script>

<template>
  <div class="global-tools">
    <button ref="trigger" class="tools-trigger" :class="{ 'has-update': updateAvailable, 'update-arriving': updateArriving }" type="button" :aria-label="updateAvailable ? '工具，有新版本可更新' : '工具'" aria-haspopup="dialog" :aria-expanded="open" aria-controls="global-tools-panel" @click="toggle">
      <i class="ri-equalizer-line" aria-hidden="true"></i><span>工具</span><span v-if="unread || updateAvailable" class="tools-dot" :aria-label="[unread && '有未读通知', updateAvailable && '有新版本'].filter(Boolean).join('，')"></span><span v-if="updateAvailable" class="tools-update-label">可更新</span>
    </button>
    <Teleport to="body">
      <section v-show="open" id="global-tools-panel" ref="panel" class="tools-panel" :style="position" role="dialog" aria-label="工具">
        <header><strong>工具</strong><button type="button" aria-label="收起工具" @click="close"><i class="ri-close-line" aria-hidden="true"></i></button></header>
        <NotificationCenter row :return-focus="trigger" @unread-change="unread = $event" @opened="open = false" />
        <VersionUpdateNotice row :anchor="trigger" @available-change="updateAvailable = $event" @arrival-change="updateArriving = $event" @opened="open = false" />
        <hr>
        <button class="tools-row" type="button" :aria-label="`外观：${themeLabel}，点击切换`" @click="theme.toggleTheme()"><i :class="theme.effective.value === 'dark' ? 'ri-moon-line' : 'ri-sun-line'" aria-hidden="true"></i><span>外观</span><small>{{ themeLabel }}</small><i class="ri-arrow-right-s-line" aria-hidden="true"></i></button>
        <button class="tools-row" type="button" @click="shortcuts"><i class="ri-keyboard-line" aria-hidden="true"></i><span>快捷键</span><i class="ri-arrow-right-s-line" aria-hidden="true"></i></button>
      </section>
    </Teleport>
  </div>
</template>

<style scoped>
.tools-trigger { display:inline-flex; align-items:center; gap:6px; height:30px; padding:0 10px; border:1px solid var(--wao-line); border-radius:16px; background:var(--wao-bg-card); color:var(--wao-text-2); font-size:12px; white-space:nowrap; cursor:pointer; }
.tools-trigger:hover, .tools-trigger[aria-expanded=true] { background:var(--wao-primary-soft); color:var(--wao-primary-text); }
.tools-dot { width:6px; height:6px; border-radius:50%; background:var(--wao-primary); }
.tools-trigger.has-update { color:var(--wao-primary-text); background:var(--wao-primary-soft); border-color:var(--wao-primary); }
.tools-update-label { border-left:1px solid var(--wao-line-control); padding-left:6px; font-size:11px; }
.tools-trigger.update-arriving { animation:tools-update-signal 2s ease-out 5; }
.tools-trigger.update-arriving > i { animation:tools-update-knock 2s ease-in-out 5; }
@keyframes tools-update-signal { 0%,100% { box-shadow:0 0 0 0 transparent; } 25% { box-shadow:0 0 0 3px color-mix(in srgb,var(--wao-primary) 35%,transparent); } 90% { box-shadow:0 0 0 18px transparent; } }
@keyframes tools-update-knock { 0%,100% { transform:translateY(0) rotate(0); } 18% { transform:translateY(-5px) rotate(-18deg); } 30% { transform:translateY(-5px) rotate(18deg); } 45% { transform:rotate(-8deg); } }
@media(prefers-reduced-motion:reduce) { .tools-trigger.update-arriving, .tools-trigger.update-arriving > i { animation:none; } }
.tools-panel { position:fixed; z-index:1000; width:min(290px, calc(100vw - 24px)); max-height:calc(100dvh - 24px); overflow:auto; box-sizing:border-box; padding:12px; border:1px solid var(--wao-line-control); border-radius:14px; background:var(--wao-bg-card); box-shadow:var(--wao-shadow-modal); }
.tools-panel > header { display:flex; align-items:center; justify-content:space-between; padding:0 6px 8px; font-size:13px; }
.tools-panel > header button { border:0; background:transparent; color:var(--wao-text-3); width:28px; height:28px; border-radius:7px; cursor:pointer; }
.tools-row { display:flex; align-items:center; gap:10px; width:100%; min-height:42px; padding:8px 10px; background:transparent; color:var(--wao-text-2); border:0; border-radius:8px; font-size:13px; text-align:left; cursor:pointer; }
.tools-row span { flex:1; } .tools-row small { color:var(--wao-text-faint); font-size:11px; }
.tools-row:hover, .tools-panel > header button:hover { background:var(--wao-bg-hover); }
hr { border:0; border-top:1px solid var(--wao-line); margin:8px 4px; }
@media(pointer:coarse) { .tools-trigger, .tools-row, .tools-panel > header button { min-height:44px; } .tools-panel > header button { min-width:44px; } }
</style>
