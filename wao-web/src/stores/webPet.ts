import { ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchPreference, savePreference } from '@/api/favorites'

export type WebPetTriggerAction = 'QUICK_ACTIONS' | 'SETTINGS' | 'NONE'

export interface WebPetSettings {
  hoverAction: WebPetTriggerAction
  leftClickAction: WebPetTriggerAction
  rightClickAction: WebPetTriggerAction
  noticeSnoozeMinutes: number
  petSnoozeMinutes: number
  noticeRotationSeconds: number
  showTaskNotices: boolean
  idleAnimation: boolean
}

export const DEFAULT_WEB_PET_SETTINGS: WebPetSettings = {
  hoverAction: 'QUICK_ACTIONS',
  leftClickAction: 'QUICK_ACTIONS',
  rightClickAction: 'SETTINGS',
  noticeSnoozeMinutes: 5,
  petSnoozeMinutes: 10,
  noticeRotationSeconds: 7,
  showTaskNotices: true,
  idleAnimation: true,
}

export const WEB_PET_PREFERENCE_KEY = 'web-pet-settings-v1'

function boundedNumber(value: unknown, fallback: number, min: number, max: number) {
  const number = Number(value)
  return Number.isFinite(number) ? Math.min(max, Math.max(min, Math.round(number))) : fallback
}

function triggerAction(value: unknown, fallback: WebPetTriggerAction): WebPetTriggerAction {
  return value === 'QUICK_ACTIONS' || value === 'SETTINGS' || value === 'NONE' ? value : fallback
}

export function normalizeWebPetSettings(value: unknown): WebPetSettings {
  const source = value && typeof value === 'object' ? value as Partial<WebPetSettings> : {}
  return {
    hoverAction: triggerAction(source.hoverAction, DEFAULT_WEB_PET_SETTINGS.hoverAction),
    leftClickAction: triggerAction(source.leftClickAction, DEFAULT_WEB_PET_SETTINGS.leftClickAction),
    rightClickAction: triggerAction(source.rightClickAction, DEFAULT_WEB_PET_SETTINGS.rightClickAction),
    noticeSnoozeMinutes: boundedNumber(source.noticeSnoozeMinutes, 5, 1, 120),
    petSnoozeMinutes: boundedNumber(source.petSnoozeMinutes, 10, 1, 120),
    noticeRotationSeconds: boundedNumber(source.noticeRotationSeconds, 7, 3, 60),
    showTaskNotices: source.showTaskNotices !== false,
    idleAnimation: source.idleAnimation !== false,
  }
}

export const useWebPetStore = defineStore('web-pet', () => {
  const settings = ref<WebPetSettings>({ ...DEFAULT_WEB_PET_SETTINGS })
  const loadState = ref<'idle' | 'loading' | 'loaded' | 'error'>('idle')
  const saveState = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
  const error = ref('')

  async function load(force = false) {
    if (loadState.value === 'loading' || (!force && loadState.value === 'loaded')) return settings.value
    loadState.value = 'loading'
    error.value = ''
    try {
      const raw = await fetchPreference(WEB_PET_PREFERENCE_KEY)
      settings.value = normalizeWebPetSettings(raw ? JSON.parse(raw) : null)
      loadState.value = 'loaded'
    } catch (cause) {
      loadState.value = 'error'
      error.value = cause instanceof Error ? cause.message : '宠物设置读取失败'
    }
    return settings.value
  }

  async function save(next: WebPetSettings) {
    const normalized = normalizeWebPetSettings(next)
    saveState.value = 'saving'
    error.value = ''
    try {
      await savePreference(WEB_PET_PREFERENCE_KEY, JSON.stringify(normalized))
      settings.value = normalized
      loadState.value = 'loaded'
      saveState.value = 'saved'
      return normalized
    } catch (cause) {
      saveState.value = 'error'
      error.value = cause instanceof Error ? cause.message : '宠物设置保存失败'
      throw cause
    }
  }

  return { settings, loadState, saveState, error, load, save }
})
