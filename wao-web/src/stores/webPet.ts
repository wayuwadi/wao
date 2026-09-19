import { ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
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

  const session = useSessionStore()
  let epoch = 0
  let revision = 0
  let pendingLoad: Promise<WebPetSettings> | undefined

  watch(() => session.userId, () => {
    epoch += 1
    revision += 1
    pendingLoad = undefined
    settings.value = { ...DEFAULT_WEB_PET_SETTINGS }
    loadState.value = 'idle'
    saveState.value = 'idle'
    error.value = ''
  }, { flush: 'sync' })

  function load(force = false): Promise<WebPetSettings> {
    if (pendingLoad) return pendingLoad
    if (!force && loadState.value === 'loaded') return Promise.resolve(settings.value)
    if (!session.userId) return Promise.resolve(settings.value)
    const owner = epoch
    const current = revision
    loadState.value = 'loading'
    error.value = ''
    const request = (async () => {
      try {
        const raw = await fetchPreference(WEB_PET_PREFERENCE_KEY)
        if (owner === epoch && current === revision) {
          settings.value = normalizeWebPetSettings(raw ? JSON.parse(raw) : null)
          loadState.value = 'loaded'
        }
      } catch (cause) {
        if (owner === epoch && current === revision) {
          loadState.value = 'error'
          error.value = cause instanceof Error ? cause.message : '宠物设置读取失败'
        }
      }
      return settings.value
    })()
    pendingLoad = request
    void request.finally(() => { if (pendingLoad === request) pendingLoad = undefined })
    return request
  }

  async function save(next: WebPetSettings) {
    if (!session.userId) throw new Error('请登录后保存宠物设置')
    if (saveState.value === 'saving') throw new Error('宠物设置正在保存，请稍候')
    const owner = epoch
    const normalized = normalizeWebPetSettings(next)
    revision += 1
    saveState.value = 'saving'
    error.value = ''
    try {
      await savePreference(WEB_PET_PREFERENCE_KEY, JSON.stringify(normalized))
      if (owner !== epoch) throw new Error('账号已切换，请在当前账号重新读取设置')
      revision += 1
      settings.value = normalized
      loadState.value = 'loaded'
      saveState.value = 'saved'
      return normalized
    } catch (cause) {
      if (owner === epoch) {
        saveState.value = 'error'
        error.value = cause instanceof Error ? cause.message : '宠物设置保存失败'
      }
      throw cause
    }
  }

  return { settings, loadState, saveState, error, load, save }
})
