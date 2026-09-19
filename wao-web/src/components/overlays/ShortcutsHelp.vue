<script setup lang="ts">
import { ref, watch } from 'vue'
import { useUiStore } from '@/stores/ui'
import { SHORTCUT_GROUPS } from '@/composables/shortcuts'
import { useShortcutSettingsStore } from '@/stores/shortcuts'
import {
  DEFAULT_SHORTCUT_SETTINGS,
  SHORTCUT_ACTIONS,
  cloneShortcutSettings,
  displayShortcut,
  shortcutFromEvent,
  type ConfigurableShortcutAction,
  type ShortcutSettings,
} from '@/utils/shortcutConfig'

const ui = useUiStore()
const store = useShortcutSettingsStore()
const draft = ref<ShortcutSettings>(cloneShortcutSettings(store.settings))
const localError = ref('')
watch(
  () => store.settings,
  (settings) => {
    draft.value = cloneShortcutSettings(settings)
  },
  { deep: true },
)
void store.load()

/** 把「⌘/Ctrl K」「G H」拆成一个个按键胶囊。 */
function keys(combo: string): string[] {
  return combo.split(' ')
}

function capture(
  scope: 'window' | 'global',
  action: ConfigurableShortcutAction,
  event: KeyboardEvent,
) {
  event.preventDefault()
  event.stopPropagation()
  localError.value = ''
  if ((event.key === 'Backspace' || event.key === 'Delete') && scope === 'global') {
    draft.value.global[action] = ''
    return
  }
  const shortcut = shortcutFromEvent(event)
  if (!shortcut) {
    if (!['Alt', 'Shift', 'Control', 'Meta'].includes(event.key))
      localError.value = '请同时按住 Ctrl/⌘、Alt/⌥ 或 Shift，再按一个字母或数字'
    return
  }
  draft.value[scope][action] = shortcut
}

async function save() {
  localError.value = ''
  try {
    await store.save(draft.value)
  } catch (cause) {
    localError.value = cause instanceof Error ? cause.message : '快捷键配置保存失败，请重试'
  }
}

function reset() {
  draft.value = cloneShortcutSettings(DEFAULT_SHORTCUT_SETTINGS)
  localError.value = ''
}
</script>

<template>
  <div class="mask" @click="ui.closeHelp()"></div>
  <div class="modal">
    <div class="head">
      <div class="title"><i class="ri-keyboard-line"></i>键盘快捷键</div>
      <div class="spacer"></div>
      <button class="close" @click="ui.closeHelp()"><i class="ri-close-line"></i></button>
    </div>

    <div class="groups">
      <div class="group configurable">
        <div class="group-title">可配置快捷键</div>
        <p class="hint">
          点输入框后直接按新组合键。系统级快捷键仅在 WAO 桌面客户端运行时生效，按 Delete 可关闭。
        </p>
        <div v-for="item in SHORTCUT_ACTIONS" :key="item.id" class="config-row">
          <span class="desc"
            ><b>{{ item.label }}</b
            ><small>{{ item.description }}</small></span
          >
          <label
            >窗口内<input
              :value="displayShortcut(draft.window[item.id])"
              readonly
              :aria-label="`${item.label}窗口内快捷键`"
              @keydown="capture('window', item.id, $event)"
          /></label>
          <label
            >系统级<input
              :value="displayShortcut(draft.global[item.id]) || '未启用'"
              readonly
              :aria-label="`${item.label}系统级快捷键`"
              @keydown="capture('global', item.id, $event)"
          /></label>
        </div>
        <div class="save-actions">
          <button type="button" :disabled="store.state === 'saving'" @click="reset">
            恢复默认
          </button>
          <button type="button" class="primary" :disabled="store.state === 'saving'" @click="save">
            {{
              store.state === 'saving'
                ? '保存中…'
                : store.state === 'error'
                  ? '重试保存'
                  : '保存配置'
            }}
          </button>
        </div>
        <p v-if="localError || store.error" class="config-error" role="alert">
          {{ localError || store.error }}
        </p>
        <p v-else-if="store.state === 'loading'" class="config-status" role="status">
          正在读取你的快捷键配置…
        </p>
        <p v-else-if="store.state === 'saved'" class="config-status saved" role="status">
          快捷键已保存并同步到桌面客户端
        </p>
      </div>
      <div v-for="g in SHORTCUT_GROUPS" :key="g.title" class="group">
        <div class="group-title">{{ g.title }}</div>
        <div v-for="(row, i) in g.items" :key="i" class="row">
          <span class="desc">{{ row[1] }}</span>
          <span class="keys">
            <kbd v-for="(k, j) in keys(row[0])" :key="j">{{ k }}</kbd>
          </span>
        </div>
      </div>
    </div>

    <div class="foot">按 <kbd>?</kbd> 或 <kbd>Esc</kbd> 关闭</div>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  background: rgba(23, 25, 35, 0.32);
  z-index: 120;
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
  width: min(600px, calc(100vw - 40px));
  max-height: 80vh;
  overflow-y: auto;
  background: var(--wao-bg-card);
  border-radius: 14px;
  z-index: 121;
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
  display: flex;
  align-items: center;
  gap: 8px;
}
.title i {
  font-size: 18px;
  color: var(--wao-primary-text);
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
.groups {
  margin-top: 14px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 28px;
}
.configurable {
  grid-column: 1 / -1;
}
.hint {
  margin: 0 0 8px;
  color: var(--wao-text-faint);
  font-size: 11px;
  line-height: 1.5;
}
.config-row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) 150px 150px;
  gap: 10px;
  align-items: center;
  padding: 7px 0;
  border-bottom: 1px solid var(--wao-divider);
}
.config-row .desc {
  display: grid;
  gap: 2px;
}
.config-row .desc small {
  color: var(--wao-text-faint);
  font-size: 10px;
}
.config-row label {
  display: grid;
  gap: 3px;
  color: var(--wao-text-faint);
  font-size: 9px;
}
.config-row input {
  width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--wao-line-strong);
  border-radius: 7px;
  padding: 7px 8px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-2);
  font: inherit;
  cursor: pointer;
}
.config-row input:focus {
  border-color: var(--wao-primary);
  outline: 2px solid var(--wao-primary-soft-2);
}
.save-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}
.save-actions button {
  border: 1px solid var(--wao-line-strong);
  border-radius: 7px;
  padding: 7px 12px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  cursor: pointer;
}
.save-actions .primary {
  border-color: var(--wao-primary);
  background: var(--wao-primary);
  color: white;
}
.config-error,
.config-status {
  margin: 8px 0 0;
  color: var(--wao-danger-strong);
  font-size: 11px;
  text-align: right;
}
.config-status {
  color: var(--wao-text-faint);
}
.config-status.saved {
  color: var(--wao-success-strong);
}
@media (max-width: 540px) {
  .groups {
    grid-template-columns: 1fr;
  }
  .config-row {
    grid-template-columns: 1fr 1fr;
  }
  .config-row .desc {
    grid-column: 1 / -1;
  }
}
.group-title {
  font-size: 12px;
  color: var(--wao-text-weak);
  letter-spacing: 0.5px;
  margin-bottom: 4px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--wao-divider);
}
.row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}
.desc {
  flex: 1;
  font-size: 13px;
  color: var(--wao-text-2);
}
.keys {
  display: inline-flex;
  gap: 4px;
  flex-shrink: 0;
}
kbd {
  font-family: inherit;
  font-size: 11px;
  min-width: 18px;
  text-align: center;
  padding: 2px 6px;
  border: 1px solid var(--wao-line-strong);
  border-bottom-width: 2px;
  border-radius: 6px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-2);
}
.foot {
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--wao-divider);
  font-size: 12px;
  color: var(--wao-text-weak);
}
.foot kbd {
  margin: 0 2px;
}
</style>
