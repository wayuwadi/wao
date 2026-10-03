<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { watch } from 'vue'
import NotificationCenter from '@/components/notifications/NotificationCenter.vue'
import VersionUpdateNotice from '@/components/ui/VersionUpdateNotice.vue'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { blockCompanions } from '@/composables/quietCompanions'

const router = useRouter()
const route = useRoute()
const open = ref(false)
watch(open, value => blockCompanions('tools', value), { flush: 'sync' })
onBeforeUnmount(() => blockCompanions('tools', false))
const unread = ref(false)
const updateAvailable = ref(false)
const updateArriving = ref(false)
const trigger = ref<HTMLButtonElement>()
const panel = ref<HTMLElement>()
const position = ref({ right: '12px', top: '60px' })
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
function preferences() { open.value = false; void router.push('/settings?tab=prefs') }
useOverlayDismiss(() => open.value, close, outside)
watch(() => route.fullPath, () => { open.value = false })
onMounted(() => { window.addEventListener('resize', place); window.addEventListener('scroll', place, true) })
onBeforeUnmount(() => { window.removeEventListener('resize', place); window.removeEventListener('scroll', place, true) })
</script>

<template>
  <div class="global-tools">
    <button ref="trigger" class="tools-trigger" :class="{ 'has-update': updateAvailable, 'update-arriving': updateArriving }" type="button" :aria-label="updateAvailable ? '动态，有新版本可更新' : '动态'" aria-haspopup="dialog" :aria-expanded="open" aria-controls="global-tools-panel" @click="toggle">
      <i class="ri-notification-3-line" aria-hidden="true"></i><span>动态</span><span v-if="unread || updateAvailable" class="tools-dot" :aria-label="[unread && '有未读通知', updateAvailable && '有新版本'].filter(Boolean).join('，')"></span><span v-if="updateAvailable" class="tools-update-label">可更新</span>
    </button>
    <Teleport to="body">
      <section v-show="open" id="global-tools-panel" ref="panel" class="tools-panel" :style="position" role="dialog" aria-label="动态">
        <header><strong>动态</strong><button type="button" aria-label="收起动态" @click="close"><i class="ri-close-line" aria-hidden="true"></i></button></header>
        <NotificationCenter row :return-focus="trigger" @unread-change="unread = $event" @opened="open = false" />
        <VersionUpdateNotice row :anchor="trigger" @available-change="updateAvailable = $event" @arrival-change="updateArriving = $event" @opened="open = false" />
        <p class="activity-hint">消息有回应，更新有来处。</p>
        <hr>
        <button class="tools-row" type="button" @click="preferences"><i class="ri-settings-3-line" aria-hidden="true"></i><span>外观与偏好</span><small>快捷键 · 陪伴</small><i class="ri-arrow-right-s-line" aria-hidden="true"></i></button>
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
.activity-hint { padding:0 10px; color:var(--wao-text-weak); font-size:11px; }
hr { border:0; border-top:1px solid var(--wao-line); margin:8px 4px; }
@media(pointer:coarse) { .tools-trigger, .tools-row, .tools-panel > header button { min-height:44px; } .tools-panel > header button { min-width:44px; } }
</style>
