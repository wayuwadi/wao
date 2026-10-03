<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
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
const emit = defineEmits<{ 'available-change': [value: boolean]; opened: [] }>()
const router = useRouter()
const route = useRoute()
const trigger = ref<HTMLButtonElement>()
const panel = ref<HTMLElement>()
const closeButton = ref<HTMLButtonElement>()
const open = ref(false)
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
const currentVersion = document.querySelector('script[data-v]')?.getAttribute('data-v')
let request = 0
let observer: ResizeObserver | undefined

function positionPanel() {
  const rect = (props.anchor ?? trigger.value)?.getBoundingClientRect()
  if (!rect) return
  const width = Math.min(380, window.innerWidth - 24)
  const top = Math.max(12, Math.min(rect.bottom + 10, window.innerHeight - 160))
  position.value = {
    left: `${Math.max(12, Math.min(rect.right - width, window.innerWidth - width - 12))}px`,
    top: `${top}px`,
    width: `${width}px`,
    maxHeight: `${Math.max(120, window.innerHeight - top - 12)}px`,
  }
}
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
  positionPanel()
  open.value = true
  emit('opened')
  if (availableVersion.value && !loaded.value) void load()
  await nextTick()
  closeButton.value?.focus()
}
function close(restoreFocus = true) {
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
  if (open.value) void load()
}
function handleReload(rawEvent: Event) {
  const state = (rawEvent as CustomEvent<WorkspaceReloadState>).detail
  busy.value = state.state !== 'failed'
  busyLabel.value = state.state === 'reloading' ? '正在更新…' : '正在保存…'
  saveError.value = state.state === 'failed' ? state.message || '保存失败，草稿已保留，请重试' : ''
}
function update() {
  if (busy.value || !availableVersion.value) return
  saveError.value = ''
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
    !trigger.value?.contains(event.target)
  )
    close(false)
}
function keydown(event: KeyboardEvent) {
  if (open.value && event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    close()
  }
}
watch(
  () => route.path,
  () => close(false),
)
onMounted(() => {
  document.body.addEventListener(WEB_UPDATE_EVENT, handleUpdate)
  window.addEventListener(WORKSPACE_RELOAD_STATE_EVENT, handleReload)
  document.addEventListener('pointerdown', outside)
  document.addEventListener('keydown', keydown, true)
  window.addEventListener('resize', positionPanel)
  window.addEventListener('scroll', positionPanel, true)
  const header = trigger.value?.closest('header')
  if (header && typeof ResizeObserver !== 'undefined') {
    observer = new ResizeObserver(positionPanel)
    observer.observe(header)
  }
})
onBeforeUnmount(() => {
  request++
  observer?.disconnect()
  document.body.removeEventListener(WEB_UPDATE_EVENT, handleUpdate)
  window.removeEventListener(WORKSPACE_RELOAD_STATE_EVENT, handleReload)
  document.removeEventListener('pointerdown', outside)
  document.removeEventListener('keydown', keydown, true)
  window.removeEventListener('resize', positionPanel)
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
        :style="position"
        role="dialog"
        aria-labelledby="version-update-title"
      >
        <header class="panel-heading">
          <div>
            <span class="eyebrow">{{ availableVersion ? '新版本已准备好' : '本次更新' }}</span>
            <h2 id="version-update-title">{{ edition?.title || '看看这次的新变化' }}</h2>
          </div>
          <button
            ref="closeButton"
            class="icon-button"
            type="button"
            aria-label="关闭更新内容"
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
        <footer>
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
            <button type="button" class="later" @click="close()">
              {{ availableVersion ? '稍后' : '收起' }}
            </button>
            <button
              v-if="availableVersion"
              class="update-button"
              type="button"
              :disabled="busy"
              @click="update"
            >
              {{ busy ? busyLabel : saveError ? '重试保存并更新' : '保存并更新' }}
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
footer {
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
  footer {
    padding: 8px 16px;
  }
  .save-hint {
    display: none;
  }
}
</style>

<style scoped>
.tool-menu-row { display:flex; align-items:center; gap:10px; width:100%; min-height:42px; height:auto; padding:8px 10px; border:0; border-radius:8px; background:transparent; color:var(--wao-text-2); font-size:13px; text-align:left; cursor:pointer; }
.tool-menu-row:hover { background:var(--wao-bg-hover); }
.tool-menu-row .trigger-label { display:inline; flex:1; }
.tool-menu-row small { color:var(--wao-text-faint); font-size:11px; }
@media(pointer:coarse) { .tool-menu-row { min-height:44px; } }
</style>
