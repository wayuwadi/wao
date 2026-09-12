import { ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchPreference, savePreference } from '@/api/favorites'
import { useSessionStore } from '@/stores/session'
import {
  cloneShortcutSettings,
  parseShortcutSettings,
  validateShortcutSettings,
  type ShortcutSettings,
} from '@/utils/shortcutConfig'
import { syncDesktopGlobalShortcuts } from '@/utils/desktopToolbar'

const PREFERENCE_KEY = 'keyboard-shortcuts-v1'

export const useShortcutSettingsStore = defineStore('shortcut-settings', () => {
  const settings = ref<ShortcutSettings>(cloneShortcutSettings())
  const state = ref<'idle' | 'loading' | 'ready' | 'saving' | 'saved' | 'error'>('idle')
  const error = ref('')
  let memberId = ''
  let version = 0

  async function load(force = false) {
    const current = useSessionStore().userId
    if (!current) return
    if (current !== memberId) {
      memberId = current
      settings.value = cloneShortcutSettings()
      state.value = 'idle'
      error.value = ''
      version++
    }
    if (!force && ['loading', 'ready', 'saving', 'saved'].includes(state.value)) return
    const request = ++version
    state.value = 'loading'
    error.value = ''
    try {
      const raw = await fetchPreference(PREFERENCE_KEY)
      if (request !== version || current !== useSessionStore().userId) return
      settings.value = parseShortcutSettings(raw ? JSON.parse(raw) : null)
      state.value = 'ready'
      syncDesktopGlobalShortcuts(settings.value.global)
    } catch (cause) {
      if (request !== version) return
      state.value = 'error'
      error.value = cause instanceof Error ? cause.message : '快捷键配置读取失败'
      syncDesktopGlobalShortcuts(settings.value.global)
    }
  }

  async function save(next: ShortcutSettings) {
    const candidate = cloneShortcutSettings(next)
    const invalid = validateShortcutSettings(candidate)
    if (invalid) throw new Error(invalid)
    const current = useSessionStore().userId
    const request = ++version
    state.value = 'saving'
    error.value = ''
    try {
      await savePreference(PREFERENCE_KEY, JSON.stringify(candidate))
      if (request !== version || current !== useSessionStore().userId) return
      settings.value = candidate
      state.value = 'saved'
      syncDesktopGlobalShortcuts(candidate.global)
    } catch (cause) {
      if (request === version) {
        state.value = 'error'
        error.value = cause instanceof Error ? cause.message : '快捷键配置保存失败，请重试'
      }
      throw cause
    }
  }

  return { settings, state, error, load, save }
})
