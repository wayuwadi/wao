<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { fetchWebRelease } from '@/api/version'
import { MODULES } from '@/config/nav'
import { CURRENT_RELEASE } from '@/data/featureCatalog'
import type { ReleaseEdition, ReleaseHighlight } from '@/types/featureExplorer'
import {
  reloadToLatestVersion,
  WEB_UPDATE_EVENT,
  WORKSPACE_RELOAD_STATE_EVENT,
  type WebUpdateEventDetail,
  type WorkspaceReloadState,
} from '@/utils/versionUpdate'

const props = defineProps<{ row?: boolean; anchor?: HTMLElement }>()
const emit = defineEmits<{ 'available-change': [value: boolean]; 'arrival-change': [value: boolean]; opened: [] }>()
const router = useRouter()
const route = useRoute()
const trigger = ref<HTMLButtonElement>()
const panel = ref<HTMLElement>()
const closeButton = ref<HTMLButtonElement>()
const open = ref(false)
const arriving = ref(false)
watch(arriving, value => emit('arrival-change', value))
const ANNOUNCED_KEY = 'wao-update-arrival-version'
let arrivalTimer: ReturnType<typeof setTimeout> | undefined
let pendingArrival = false
let motionPreference: MediaQueryList | undefined
const availableVersion = ref('')
watch(availableVersion, value => emit('available-change', !!value), { immediate: true })
const upcoming = ref<ReleaseEdition | null>(null)
const loading = ref(false)
const loaded = ref(false)
const loadError = ref('')
const saveError = ref('')
const busy = ref(false)
const busyLabel = ref('正在保存…')
const position = ref({ left: '12px', top: '60px', width: '360px', maxHeight: '500px' })
const edition = computed(() => (availableVersion.value ? upcoming.value : CURRENT_RELEASE))
const featureArrival = computed(() => !!availableVersion.value && !!upcoming.value && upcoming.value.attention !== 'maintenance')
const currentVersion = document.querySelector('script[data-v]')?.getAttribute('data-v')
let request = 0
let observer: ResizeObserver | undefined

function positionPanel() {
  const rect = (props.anchor ?? trigger.value)?.getBoundingClientRect()
  const width = Math.min(380, window.innerWidth - 24)
  // 窄屏完整菜单可能尚未打开；隐藏入口不能成为浮层的定位锚点。
  const visibleAnchor = rect && rect.width > 0 && rect.height > 0
  const top = visibleAnchor ? Math.max(12, Math.min(rect.bottom + 10, window.innerHeight - 260)) : 12
  position.value = {
    left: `${Math.max(12, Math.min((visibleAnchor ? rect.right : window.innerWidth - 12) - width, window.innerWidth - width - 12))}px`,
    top: `${top}px`,
    width: `${width}px`,
    maxHeight: `${Math.max(120, window.innerHeight - top - 12)}px`,
  }
}
function stopArrival() {
  clearTimeout(arrivalTimer)
  arriving.value = false
}
function announce() {
  if (!pendingArrival || document.hidden || busy.value) return
  if (!loaded.value) { void load(); return }
  pendingArrival = false
  if (!upcoming.value || upcoming.value.attention === 'maintenance') return
  try { sessionStorage.setItem(ANNOUNCED_KEY, availableVersion.value) } catch { /* 本标签页内仍由版本去重。 */ }
  positionPanel()
  open.value = true
  emit('opened')
  stopArrival()
  if (!motionPreference?.matches) {
    arriving.value = true
    arrivalTimer = setTimeout(stopArrival, 10_000)
  }
  // 自动提醒不移动焦点，用户可以继续输入。
}
function visibilityChanged() {
  if (document.hidden) stopArrival()
  else announce()
}
function motionChanged() { if (motionPreference?.matches) stopArrival() }
function viewportChanged() { void nextTick(positionPanel) }
async function load() {
  const target = availableVersion.value
  if (!target || loading.value) return
  const id = ++request
  loading.value = true
  loadError.value = ''
  try {
    const result = await fetchWebRelease()
    if (id !== request) return
    if (result.version !== target) throw new Error('版本正在切换，请稍后重试读取更新内容')
    upcoming.value = result.release
    loaded.value = true
    announce()
  } catch (cause) {
    if (id === request)
      loadError.value = cause instanceof Error ? cause.message : '更新内容读取失败，请重试'
  } finally {
    if (id === request) loading.value = false
  }
}
async function show() {
  if (open.value) {
    close()
    return
  }
  // 手动查看接管待提醒状态，慢响应不能在用户收起后重新弹出。
  pendingArrival = false
  if (availableVersion.value) {
    try { sessionStorage.setItem(ANNOUNCED_KEY, availableVersion.value) } catch { /* 当前页面仍可去重。 */ }
  }
  positionPanel()
  open.value = true
  emit('opened')
  if (availableVersion.value && !loaded.value) void load()
  await nextTick()
  closeButton.value?.focus()
}
function close(restoreFocus = true) {
  if (busy.value) return
  stopArrival()
  open.value = false
  if (restoreFocus) (props.anchor ?? trigger.value)?.focus()
}
function browseHistory() {
  close(false)
  // 同一路由和锚点不会再次触发路由监听，也要让重复点击回到时间线开头。
  if (route.path === '/features')
    document.getElementById('recent-updates')?.scrollIntoView({ block: 'start' })
}
function handleUpdate(rawEvent: Event) {
  const value = (rawEvent as CustomEvent<WebUpdateEventDetail>).detail?.version
  if (typeof value !== 'string') return
  const version = value.trim()
  if (
    !version ||
    version.length > 160 ||
    version === availableVersion.value ||
    version === currentVersion
  )
    return
  request++
  availableVersion.value = version
  upcoming.value = null
  loaded.value = false
  loading.value = false
  loadError.value = ''
  saveError.value = ''
  let announced = ''
  try { announced = sessionStorage.getItem(ANNOUNCED_KEY) || '' } catch { /* 存储不可用时不阻断提醒。 */ }
  pendingArrival = announced !== version
  if (pendingArrival) announce()
  else if (open.value) void load()
}
function handleReload(rawEvent: Event) {
  const state = (rawEvent as CustomEvent<WorkspaceReloadState>).detail
  stopArrival()
  busy.value = state.state !== 'failed'
  busyLabel.value = state.state === 'reloading' ? '正在更新…' : '正在保存…'
  saveError.value = state.state === 'failed' ? state.message || '保存失败，草稿已保留，请重试' : ''
  if (state.state === 'failed' && pendingArrival) announce()
}
function update() {
  if (busy.value || !availableVersion.value) return
  saveError.value = ''
  stopArrival()
  reloadToLatestVersion(availableVersion.value)
}
function feature(item: ReleaseHighlight) {
  return Object.prototype.hasOwnProperty.call(MODULES, item.featureId)
    ? MODULES[item.featureId]
    : undefined
}
async function experience(item: ReleaseHighlight) {
  const module = feature(item)
  if (!module || availableVersion.value) return
  await router.push(module.path)
  close(false)
}
function outside(event: PointerEvent) {
  if (
    event.target instanceof Node &&
    !panel.value?.contains(event.target) &&
    !trigger.value?.contains(event.target) &&
    !props.anchor?.contains(event.target)
  )
    close(false)
}
useOverlayDismiss(() => open.value, () => close(), outside)
watch(
  () => route.path,
  () => close(false),
)
onMounted(() => {
  motionPreference = window.matchMedia?.('(prefers-reduced-motion: reduce)')
  motionPreference?.addEventListener('change', motionChanged)
  document.addEventListener('visibilitychange', visibilityChanged)
  document.body.addEventListener(WEB_UPDATE_EVENT, handleUpdate)
  window.addEventListener(WORKSPACE_RELOAD_STATE_EVENT, handleReload)
  window.addEventListener('resize', viewportChanged)
  window.addEventListener('scroll', positionPanel, true)
  const header = (props.anchor ?? trigger.value)?.closest('header')
  if (header && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(positionPanel)
    observer.observe(header)
  }
})
onBeforeUnmount(() => {
  stopArrival()
  motionPreference?.removeEventListener('change', motionChanged)
  document.removeEventListener('visibilitychange', visibilityChanged)
  request++
  observer?.disconnect()
  document.body.removeEventListener(WEB_UPDATE_EVENT, handleUpdate)
  window.removeEventListener(WORKSPACE_RELOAD_STATE_EVENT, handleReload)
  window.removeEventListener('resize', viewportChanged)
  window.removeEventListener('scroll', positionPanel, true)
})
</script>

<template>
  <div class="version-update-notice">
    <button
      ref="trigger"
      class="update-entry"
      type="button"
      :class="{ available: availableVersion, 'tool-menu-row': row }"
      :aria-label="availableVersion ? '有新版本，查看本次更新' : '查看本次更新'"
      :title="availableVersion ? '有新版本，查看本次更新' : '查看本次更新'"
      :aria-expanded="open"
      aria-controls="version-update-panel"
      aria-haspopup="dialog"
      @click="show"
    >
      <i :class="availableVersion ? 'ri-refresh-line' : 'ri-sparkling-line'" aria-hidden="true"></i>
      <span class="trigger-label">{{ row ? '更新记录' : availableVersion ? '有更新' : '本次更新' }}</span>
      <small v-if="row">{{ availableVersion ? '有新版本' : '本次与历史' }}</small>
      <i v-if="row" class="ri-arrow-right-s-line" aria-hidden="true"></i>
      <span v-if="availableVersion" class="update-dot" aria-hidden="true"></span>
    </button>
    <span class="sr-only" role="status" aria-live="polite">{{
      availableVersion ? '发现新版本，可在顶栏查看更新内容' : ''
    }}</span>
    <Teleport to="body">
      <section
        v-if="open"
        id="version-update-panel"
        ref="panel"
        class="update-panel"
        :class="{ 'has-new-version': availableVersion, arriving }"
        :style="position"
        role="dialog"
        aria-labelledby="version-update-title"
      >
        <div v-if="featureArrival" class="arrival-callout" aria-hidden="true"><span>✦</span> 叮！新功能来敲门了 <span>✦</span></div>
        <div v-if="arriving" class="arrival-orbit" aria-hidden="true"><span v-for="n in 6" :key="n"></span></div>
        <header class="panel-heading">
          <div v-if="featureArrival" class="arrival-hero">
            <span class="messenger" aria-hidden="true"><span class="messenger-eyes"></span><span class="messenger-smile"></span><span class="messenger-spark">✦</span></span>
            <div><span class="eyebrow">新版本已就绪</span><h2 id="version-update-title">嘿，我又长本领了。</h2><p>新功能已经送到，刷新就能拆开。</p></div>
          </div>
          <div v-else>
            <span class="eyebrow">本次更新</span>
            <h2 id="version-update-title">{{ edition?.title || '看看这次的新变化' }}</h2>
          </div>
          <button
            ref="closeButton"
            class="icon-button"
            type="button"
            aria-label="关闭更新内容"
            :disabled="busy"
            @click="close()"
          >
            <i class="ri-close-line" aria-hidden="true"></i>
          </button>
        </header>
        <div class="panel-content" :aria-busy="loading">
          <p v-if="loading" role="status">正在读取本次更新内容…</p>
          <div v-else-if="loadError" class="error" role="alert">
            <p>{{ loadError }}</p>
            <button type="button" class="text-button" @click="load">重试读取</button>
          </div>
          <template v-else-if="edition">
            <h3 v-if="availableVersion" class="release-title">{{ edition.title }}</h3>
            <p class="intro">{{ edition.intro }}</p>
            <p class="edition-date">{{ edition.publishedOn }}</p>
            <ul v-if="edition.highlights.length" class="highlights">
              <li v-for="(item, index) in edition.highlights" :key="`${item.featureId}-${index}`">
                <span class="feature-icon"
                  ><i :class="feature(item)?.icon || 'ri-sparkling-line'" aria-hidden="true"></i
                ></span>
                <div class="feature-copy">
                  <span class="eyebrow">{{ item.eyebrow }}</span>
                  <h3>{{ item.title }}</h3>
                  <p>{{ item.summary }}</p>
                  <div v-if="edition.id.startsWith('git-release-')" class="commit-points">
                    <p v-for="point in item.points" :key="point">{{ point }}</p>
                  </div>
                  <button
                    v-if="!availableVersion && feature(item)"
                    type="button"
                    class="text-button"
                    :aria-label="`去体验：${item.title}`"
                    @click="experience(item)"
                  >
                    去体验<i class="ri-arrow-right-up-line" aria-hidden="true"></i>
                  </button>
                </div>
              </li>
            </ul>
            <p v-else>本次没有新增功能说明，可继续使用现有功能。</p>
          </template>
          <p v-else-if="loaded">本次版本暂未附带更新说明，仍可安全保存并更新。</p>
        </div>
        <footer class="update-footer">
          <p v-if="saveError" class="error" role="alert">{{ saveError }}</p>
          <p v-else class="save-hint">
            {{
              availableVersion
                ? '先保存当前内容，再更新体验新功能。'
                : '随时从顶栏回看，选择感兴趣的功能体验。'
            }}
          </p>
          <div class="panel-actions">
            <RouterLink class="text-button" :to="{ path: '/features', hash: '#recent-updates' }" @click="browseHistory">最近更新</RouterLink>
            <button type="button" class="later" :disabled="busy" @click="close()">
              {{ availableVersion ? '稍后再说' : '收起' }}
            </button>
            <button
              v-if="availableVersion"
              class="update-button"
              type="button"
              :disabled="busy"
              @click="update"
            >
              <i class="ri-refresh-line" aria-hidden="true"></i>{{ busy ? busyLabel : saveError ? '重试保存并刷新' : '保存并刷新' }}
            </button>
            <RouterLink v-else class="explore-link" to="/features" @click="close(false)"
              >探索全部功能<i class="ri-arrow-right-up-line" aria-hidden="true"></i
            ></RouterLink>
          </div>
        </footer>
      </section>
    </Teleport>
  </div>
</template>

<style scoped>
.version-update-notice {
  display: flex;
  flex: none;
}
.update-entry {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--wao-line);
  border-radius: 20px;
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
  font: inherit;
  font-size: 12px;
  white-space: nowrap;
  cursor: pointer;
}
.update-entry.available {
  background: var(--wao-primary-soft);
  border-color: transparent;
  color: var(--wao-primary);
}
.update-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--wao-primary);
}
.update-panel {
  position: fixed;
  z-index: 1200;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  border: 1px solid var(--wao-line);
  border-radius: 16px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  box-shadow: var(--wao-shadow-pop-2);
  overflow: hidden;
  font-size: 13px;
}
.panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding: 20px 20px 12px;
  flex: none;
}
.eyebrow {
  color: var(--wao-text-weak);
  font-size: 11px;
}
h2 {
  margin: 5px 0 0;
  font-size: 17px;
  line-height: 1.5;
  font-weight: 600;
}
.icon-button {
  display: grid;
  place-items: center;
  flex: none;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--wao-text-weak);
  cursor: pointer;
}
.panel-content {
  padding: 0 20px;
  overflow-y: auto;
  min-height: 0;
  overflow-wrap: anywhere;
}
.intro {
  margin: 0;
  color: var(--wao-text-weak);
  line-height: 1.7;
}
.edition-date {
  margin: 8px 0 12px;
  font-size: 11px;
  color: var(--wao-text-weak);
}
.commit-points p { overflow-wrap: anywhere; font-size: 12px; line-height: 1.6; margin: 8px 0; }
.highlights {
  list-style: none;
  padding: 0;
  margin: 0;
}
.highlights li {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 16px 0;
  border-top: 1px solid var(--wao-line);
}
.feature-icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  color: var(--wao-primary);
  background: var(--wao-primary-soft);
}
.feature-copy {
  min-width: 0;
}
h3 {
  margin: 3px 0 6px;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.6;
}
.feature-copy p {
  margin: 0;
  color: var(--wao-text-weak);
  font-size: 12px;
  line-height: 1.7;
}
.text-button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 0;
  border: 0;
  background: transparent;
  color: var(--wao-primary);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.update-footer {
  padding: 12px 20px 16px;
  border-top: 1px solid var(--wao-line);
  flex: none;
}
.save-hint {
  color: var(--wao-text-weak);
  font-size: 11px;
  line-height: 1.6;
  margin: 0 0 12px;
}
.panel-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}
.panel-actions button,
.panel-actions a,
.explore-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 34px;
  padding: 0 12px;
  border-radius: 8px;
  font: inherit;
  font-size: 12px;
  text-decoration: none;
  cursor: pointer;
}
.later {
  border: 0;
  background: transparent;
  color: var(--wao-text-weak);
}
.update-button,
.explore-link {
  border: 0;
  background: var(--wao-primary);
  color: #fff;
}
.update-button:disabled {
  opacity: 0.65;
  cursor: wait;
}
.error {
  color: var(--wao-danger-strong);
  line-height: 1.7;
  font-size: 12px;
}
button:focus-visible,
a:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 3px;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
@container app-main (max-width: 560px) {
  .trigger-label {
    display: none;
  }
  .update-entry {
    width: 32px;
    height: 32px;
    padding: 0;
    justify-content: center;
  }
  .update-dot {
    position: absolute;
    right: 3px;
    top: 3px;
  }
}
@media (max-height: 420px) {
  .panel-heading {
    padding: 10px 16px 6px;
  }
  h2 {
    font-size: 14px;
    margin-top: 0;
  }
  .update-footer {
    padding: 8px 16px;
  }
  .save-hint {
    display: none;
  }
}
</style>

<style scoped>
.update-panel.has-new-version {
  border-color:color-mix(in srgb,var(--wao-primary) 32%,var(--wao-line));
  transform-origin:90% 0;
  box-shadow:var(--wao-shadow-pop-2),0 0 0 3px color-mix(in srgb,var(--wao-primary) 6%,transparent);
}
.arrival-callout {
  display:flex; justify-content:center; align-items:center; gap:12px; flex:none;
  padding:8px 16px; color:#fff; background:linear-gradient(110deg,var(--wao-primary),#8570d9,var(--wao-primary));
  background-size:200% 100%; font-size:12px; font-weight:600;
}
.arrival-hero { display:flex; align-items:center; gap:14px; min-width:0; }
.has-new-version .panel-heading { padding:22px 18px 18px; background:linear-gradient(125deg,var(--wao-primary-soft),var(--wao-bg-card) 85%); }
.arrival-hero h2 { font-size:20px; margin:4px 0 6px; letter-spacing:-.6px; }
.arrival-hero .eyebrow { color:var(--wao-primary-text); }
.arrival-hero p { color:var(--wao-text-weak); font-size:12px; margin:0; }
.messenger {
  position:relative; flex:none; width:48px; height:52px; display:block; border-radius:18px 18px 16px 8px;
  transform:rotate(-8deg); background:linear-gradient(145deg,#8995ff,var(--wao-primary) 68%);
  box-shadow:0 8px 16px color-mix(in srgb,var(--wao-primary) 25%,transparent);
}
.messenger-eyes { position:absolute; top:19px; left:13px; width:5px; height:7px; border-radius:5px; background:#fff; box-shadow:17px 0 0 #fff; }
.messenger-smile { position:absolute; top:30px; left:20px; width:10px; height:6px; border-bottom:2px solid #fff; border-radius:0 0 9px 9px; }
.messenger-spark { position:absolute; top:-16px; right:-11px; color:#efb64e; font-size:25px; }
.release-title { margin:16px 0 8px; }
.has-new-version .update-button { box-shadow:0 4px 12px color-mix(in srgb,var(--wao-primary) 20%,transparent); }
.icon-button:disabled,.later:disabled { opacity:.5; cursor:wait; }
.arrival-orbit { position:absolute; top:50px; right:0; width:100%; height:240px; overflow:hidden; pointer-events:none; z-index:1; }
.arrival-orbit > span { position:absolute; top:35px; right:35px; width:7px; height:7px; border-radius:2px; background:var(--wao-primary); opacity:0; --dx:-150px; --dy:50px; --delay:0s; }
.arrival-orbit > span:nth-child(2) { --dx:-270px; --dy:100px; --delay:.15s; background:#edaf44; border-radius:50%; }
.arrival-orbit > span:nth-child(3) { --dx:-85px; --dy:145px; --delay:.3s; background:#62c9b9; }
.arrival-orbit > span:nth-child(4) { --dx:20px; --dy:180px; --delay:.25s; background:#a68beb; border-radius:50%; }
.arrival-orbit > span:nth-child(5) { --dx:-220px; --dy:145px; --delay:.4s; background:#a68beb; }
.arrival-orbit > span:nth-child(6) { --dx:-40px; --dy:60px; --delay:.1s; background:#edaf44; border-radius:50%; }
.update-panel.arriving { animation:update-card-arrival 10s cubic-bezier(.2,.7,.25,1) both; }
.arriving .arrival-callout { animation:update-callout-shimmer 3.33s ease-in-out 3; }
.arriving .messenger { animation:update-messenger-wave 2.5s ease-in-out 4; }
.arriving .messenger-eyes { animation:update-messenger-blink 3.33s ease-in-out 3; }
.arriving .messenger-spark { animation:update-star-twinkle 2.5s ease-in-out 4; }
.arriving .arrival-orbit > span { animation:update-confetti 5s var(--delay) ease-out 2; }
@keyframes update-card-arrival {
  0% { opacity:0; transform:translate(25px,-35px) scale(.62) rotate(6deg); }
  8% { opacity:1; transform:translate(-2px,6px) scale(1.018) rotate(-1deg); }
  14%,32%,55%,78%,100% { transform:translate(0,0) scale(1) rotate(0); }
  23%,45% { transform:translateY(-10px) rotate(-.4deg); }
  67% { transform:translateY(-6px) rotate(.3deg); }
}
@keyframes update-messenger-wave { 0%,100% { transform:rotate(-8deg); } 25% { transform:translateY(-8px) rotate(12deg) scale(1.07); } 50% { transform:rotate(-12deg); } 75% { transform:rotate(5deg); } }
@keyframes update-messenger-blink { 0%,40%,48%,100% { transform:scaleY(1); } 44% { transform:scaleY(.15); } }
@keyframes update-star-twinkle { 0%,100% { transform:scale(.8) rotate(0); } 50% { transform:scale(1.25) rotate(45deg); } }
@keyframes update-callout-shimmer { to { background-position:200% 0; } }
@keyframes update-confetti { 0%,10% { opacity:0; transform:translate(0,0) rotate(0) scale(.5); } 14% { opacity:.95; } 48% { opacity:.8; } 70%,100% { opacity:0; transform:translate(var(--dx),var(--dy)) rotate(230deg) scale(.5); } }
@media(max-width:380px) { .arrival-hero { gap:10px; } .arrival-hero h2 { font-size:17px; } .has-new-version .panel-heading { padding:16px 12px; gap:4px; } .messenger { width:42px; height:48px; } .messenger-eyes { left:10px; } .messenger-smile { left:17px; } }
@media(max-height:480px) { .arrival-callout { padding:4px 12px; } .has-new-version .panel-heading { padding:8px 12px; } .messenger { display:none; } .arrival-hero h2 { font-size:16px; margin:0; } .arrival-hero p { display:none; } }
@media(pointer:coarse) { .icon-button { min-width:44px; min-height:44px; } .panel-actions button,.panel-actions a { min-height:44px; } }
@media(prefers-reduced-motion:reduce) { .update-panel.arriving,.arriving .arrival-callout,.arriving .messenger,.arriving .messenger-eyes,.arriving .messenger-spark,.arriving .arrival-orbit > span { animation:none; } .arrival-orbit { display:none; } }
</style>

<style scoped>
.tool-menu-row { display:flex; align-items:center; gap:10px; width:100%; min-height:42px; height:auto; padding:8px 10px; border:0; border-radius:8px; background:transparent; color:var(--wao-text-2); font-size:13px; text-align:left; cursor:pointer; }
.tool-menu-row:hover { background:var(--wao-bg-hover); }
.tool-menu-row .trigger-label { display:inline; flex:1; }
.tool-menu-row small { color:var(--wao-text-faint); font-size:11px; }
@media(pointer:coarse) { .tool-menu-row { min-height:44px; } }
</style>
