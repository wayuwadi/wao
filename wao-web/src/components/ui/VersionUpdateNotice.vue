<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import {
  reloadToLatestVersion,
  WEB_UPDATE_EVENT,
  type WebUpdateEventDetail,
} from '@/utils/versionUpdate'

const SNOOZE_MS = 5 * 60_000

const visible = ref(false)
const availableVersion = ref('')
const refreshButton = ref<HTMLButtonElement>()

let snoozedVersion = ''
let snoozedUntil = 0
let previousFocus: HTMLElement | null = null

function handleUpdate(rawEvent: Event) {
  const event = rawEvent as CustomEvent<WebUpdateEventDetail>
  const nextVersion = event.detail?.version?.trim()
  if (!nextVersion) return
  if (nextVersion === snoozedVersion && Date.now() < snoozedUntil) return

  previousFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  availableVersion.value = nextVersion
  visible.value = true
  void nextTick(() => refreshButton.value?.focus())
}

function snooze() {
  snoozedVersion = availableVersion.value
  snoozedUntil = Date.now() + SNOOZE_MS
  visible.value = false
  void nextTick(() => previousFocus?.focus())
}

function refresh() {
  reloadToLatestVersion()
}

onMounted(() => document.body.addEventListener(WEB_UPDATE_EVENT, handleUpdate))
onUnmounted(() => document.body.removeEventListener(WEB_UPDATE_EVENT, handleUpdate))
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="version-update-backdrop" @click.self="snooze">
      <section
        class="version-update-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="version-update-title"
        aria-describedby="version-update-description"
        @keydown.esc="snooze"
      >
        <button class="version-update-close" type="button" aria-label="稍后提醒" @click="snooze">
          <i class="ri-close-line" aria-hidden="true"></i>
        </button>

        <header>
          <span class="version-update-icon" aria-hidden="true">
            <i class="ri-arrow-up-circle-fill"></i>
          </span>
          <h2 id="version-update-title">版本更新</h2>
        </header>

        <p id="version-update-description">
          检测到新版本已发布，刷新后即可使用最新内容。若正在编辑，请先保存或复制未保存内容。
        </p>

        <div class="version-update-actions">
          <button type="button" class="version-update-later" @click="snooze">稍后提醒</button>
          <button ref="refreshButton" type="button" class="version-update-refresh" @click="refresh">
            立即刷新
          </button>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.version-update-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1200;
  display: grid;
  place-items: center;
  padding: 20px;
  background: rgba(13, 17, 35, 0.56);
  backdrop-filter: blur(3px);
}

.version-update-dialog {
  position: relative;
  width: min(440px, 100%);
  padding: 28px;
  border: 1px solid var(--wao-line);
  border-radius: 18px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
  color: var(--wao-text-1);
}

.version-update-dialog header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.version-update-dialog h2 {
  margin: 0;
  font-size: 21px;
}

.version-update-icon {
  color: #e9a11b;
  font-size: 22px;
  line-height: 1;
}

.version-update-dialog p {
  margin: 22px 0 26px;
  color: var(--wao-text-2);
  font-size: 14px;
  line-height: 1.75;
}

.version-update-close {
  position: absolute;
  top: 16px;
  right: 16px;
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
  font-size: 20px;
}

.version-update-close:hover,
.version-update-close:focus-visible {
  background: var(--wao-bg-sub-2);
  color: var(--wao-text-1);
}

.version-update-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.version-update-actions button {
  min-height: 38px;
  padding: 0 18px;
  border-radius: 9px;
  cursor: pointer;
  font: inherit;
  font-weight: 700;
}

.version-update-later {
  border: 1px solid var(--wao-line);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
}

.version-update-refresh {
  border: 1px solid var(--wao-primary);
  background: var(--wao-primary);
  color: #fff;
}

.version-update-actions button:focus-visible {
  outline: 3px solid var(--wao-primary-soft);
  outline-offset: 2px;
}

@media (max-width: 520px) {
  .version-update-dialog {
    padding: 24px 20px 20px;
  }

  .version-update-actions {
    flex-direction: column-reverse;
  }

  .version-update-actions button {
    width: 100%;
  }
}
</style>
