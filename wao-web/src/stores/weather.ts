import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useSessionStore } from './session'
import { fetchPreference, savePreference } from '@/api/favorites'
import { fetchWeather, type WeatherForecast, type WeatherLocation } from '@/api/weather'
import { weatherErrorMessage } from '@/utils/weather'

export interface WeatherPreferences { location: WeatherLocation | null; rhythmVisible: boolean; calendarVisible: boolean; readingsVisible: boolean }
const defaults = (): WeatherPreferences => ({ location: { name: '武汉', latitude: 30.59, longitude: 114.31 }, rhythmVisible: true, calendarVisible: true, readingsVisible: true })
const copy = (value: WeatherPreferences): WeatherPreferences => JSON.parse(JSON.stringify(value))
export function parseWeatherPreferences(raw: string): WeatherPreferences {
  const result = defaults()
  try {
    const value = JSON.parse(raw)
    for (const key of ['rhythmVisible', 'calendarVisible', 'readingsVisible'] as const) if (typeof value[key] === 'boolean') result[key] = value[key]
    if (value.location === null) result.location = null
    const p = value.location
    if (p && typeof p.name === 'string' && p.name.length <= 160 && Number.isFinite(p.latitude) && Math.abs(p.latitude) <= 90 && Number.isFinite(p.longitude) && Math.abs(p.longitude) <= 180)
      result.location = { name: p.name, latitude: Math.round(p.latitude * 100) / 100, longitude: Math.round(p.longitude * 100) / 100 }
  } catch { /* 首次使用按产品默认城市武汉展示；已有位置与主动清空保持不变。 */ }
  return result
}

export const useWeatherStore = defineStore('weather', () => {
  const session = useSessionStore()
  const prefs = ref(defaults()), draft = ref(defaults()), query = ref('')
  const loaded = ref(false), loading = ref(false), saving = ref(false), dirty = ref(false)
  const error = ref(''), message = ref(''), forecastError = ref(''), forecasting = ref(false), stale = ref(false)
  const forecast = ref<WeatherForecast | null>(null)
  const days = computed(() => stale.value ? [] : forecast.value?.days ?? [])
  let member = '', version = 0, forecastVersion = 0, applying = false
  const key = () => `wao-weather-draft-v1:${member}`
  function keepDraft() {
    try { localStorage.setItem(key(), JSON.stringify({ prefs: draft.value, query: query.value })) }
    catch { message.value = '浏览器无法保留草稿，请在离开前保存' }
  }
  watch([draft, query], () => {
    if (applying || !member) return
    dirty.value = true; message.value = '修改尚未保存'; keepDraft()
  }, { deep: true, flush: 'sync' })
  function applyDraft(value: WeatherPreferences) { applying = true; draft.value = copy(value); applying = false }
  async function load() {
    if (!member || loading.value) return
    const request = version
    loading.value = true; error.value = ''
    try {
      const saved = parseWeatherPreferences(await fetchPreference('weather-v1'))
      if (request !== version) return
      prefs.value = saved
      if (!dirty.value) applyDraft(saved)
      loaded.value = true
      await refresh()
    } catch (e) { if (request === version) error.value = weatherErrorMessage(e, '天气设置读取失败') }
    finally { if (request === version) loading.value = false }
  }
  async function save() {
    if (saving.value || !loaded.value) return
    const request = version, saved = parseWeatherPreferences(JSON.stringify(draft.value))
    saving.value = true; error.value = ''; message.value = ''
    try {
      await savePreference('weather-v1', JSON.stringify(saved))
      if (request !== version) return
      prefs.value = saved; applyDraft(saved); dirty.value = false
      applying = true; query.value = ''; applying = false
      try { localStorage.removeItem(key()) } catch { /* 服务端保存成功仍应显示成功。 */ }
      message.value = '天气设置已保存'
      await refresh()
    } catch (e) { if (request === version) { error.value = weatherErrorMessage(e, '保存失败，请重试'); keepDraft() } }
    finally { if (request === version) saving.value = false }
  }
  async function setCalendarVisibility(field: 'calendarVisible' | 'readingsVisible', visible: boolean): Promise<boolean> {
    if (!loaded.value || saving.value) return false
    const request = version, previous = copy(prefs.value), hadDraft = dirty.value
    const next = { ...previous, [field]: visible }
    prefs.value = next
    if (hadDraft) draft.value = { ...draft.value, [field]: visible }
    else applyDraft(next)
    saving.value = true; error.value = ''; message.value = ''
    try {
      await savePreference('weather-v1', JSON.stringify(next))
      if (request !== version) return false
      message.value = hadDraft ? '日历显示已保存；其他天气设置尚未保存' : '日历显示已保存'
      if (field === 'calendarVisible') await refresh()
      return true
    } catch (e) {
      if (request === version) {
        prefs.value = previous
        if (hadDraft) draft.value = { ...draft.value, [field]: previous[field] }
        else applyDraft(previous)
        error.value = weatherErrorMessage(e, '保存失败，请重试')
        if (field === 'calendarVisible') await refresh()
      }
      return false
    } finally { if (request === version) saving.value = false }
  }
  async function refresh() {
    const request = ++forecastVersion, location = prefs.value.location
    forecast.value = null; stale.value = false; forecastError.value = ''; forecasting.value = false
    // 两个开关只控制日历和节奏页；首页的天气速览仍使用成员保存的位置。
    if (!location) return
    forecasting.value = true
    try { const value = await fetchWeather(location); if (request === forecastVersion) { forecast.value = value; checkFreshness() } }
    catch (e) { if (request === forecastVersion) forecastError.value = weatherErrorMessage(e, '天气读取失败，请重试') }
    finally { if (request === forecastVersion) forecasting.value = false }
  }
  function checkFreshness() {
    if (forecast.value) stale.value = !Number.isFinite(Date.parse(forecast.value.fetchedAt)) || Date.now() - Date.parse(forecast.value.fetchedAt) >= 30 * 60_000
  }
  function resetDraft() {
    applyDraft(prefs.value); applying = true; query.value = ''; applying = false; dirty.value = false
    message.value = '已恢复保存的设置'; error.value = ''
    try { localStorage.removeItem(key()) } catch { /* 不影响已读服务端值。 */ }
  }
  watch(() => session.currentUser.id, (id) => {
    version++; forecastVersion++; member = id || ''
    loaded.value = false; loading.value = false; saving.value = false; dirty.value = false
    prefs.value = defaults(); applyDraft(defaults()); forecast.value = null; error.value = ''; message.value = ''
    applying = true; query.value = ''; applying = false
    if (member) {
      try {
        const raw = localStorage.getItem(key())
        if (raw) { const value = JSON.parse(raw); applyDraft(parseWeatherPreferences(JSON.stringify(value.prefs))); applying = true; query.value = String(value.query || '').slice(0, 80); applying = false; dirty.value = true; message.value = '已恢复未保存的天气设置' }
      } catch { applying = false }
      void load()
    }
  }, { immediate: true })
  return { prefs, draft, query, loaded, loading, saving, dirty, error, message, forecasting, forecastError, forecast, stale, days, load, save, setCalendarVisibility, refresh, checkFreshness, resetDraft }
})
