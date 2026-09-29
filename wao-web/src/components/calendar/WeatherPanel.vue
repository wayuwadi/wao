<script setup lang="ts">
import { useOverlayDismiss } from '@/composables/overlayDismiss'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { Modal } from 'ant-design-vue'
import { useWeatherStore } from '@/stores/weather'
import { searchWeatherLocations, type WeatherLocation } from '@/api/weather'
import { weatherAdvice, weatherName, weatherTemperature, weatherErrorMessage, weatherGlance } from '@/utils/weather'
const props = defineProps<{ surface: 'rhythm' | 'calendar'; date: string; toolbar?: boolean; glance?: boolean }>()
const weather = useWeatherStore()
const settingsOpen = ref(false), searching = ref(false), locating = ref(false), searched = ref(false), locationError = ref('')
const locations = ref<WeatherLocation[]>([])
const visible = computed(() => props.surface === 'rhythm' ? weather.prefs.rhythmVisible : weather.prefs.calendarVisible)
const showPanel = computed(() => props.glance || props.surface !== 'calendar' || !props.toolbar || visible.value)
const today = computed(() => weather.days.find(day => day.date === props.date))
const compact = computed(() => props.surface === 'rhythm' || props.toolbar)
const twoDays = computed(() => [0, 1].map(offset => {
  const date = new Date(`${props.date}T00:00:00+08:00`)
  date.setUTCDate(date.getUTCDate() + offset)
  const key = new Date(date.getTime() + 8 * 60 * 60_000).toISOString().slice(0, 10)
  return { label: offset ? '明日' : '今日', date: key, forecast: weather.days.find(day => day.date === key) }
}))
const advice = computed(() => today.value ? weatherAdvice(today.value) : '')
const glanceCondition = computed(() => today.value ? weatherGlance(today.value.code) : null)
const glanceCity = computed(() => weather.prefs.location?.name.split(/\s*[·,，]\s*/)[0]?.trim() || '未设城市')
const glanceLabel = computed(() => {
  if (!weather.loaded) return weather.error ? '天气设置读取失败' : '天气读取中'
  if (!weather.prefs.location) return '请设置城市'
  if (weather.forecasting) return '更新中'
  if (weather.forecastError || weather.stale) return '暂不可用'
  return glanceCondition.value?.text || '暂无预报'
})
let sequence = 0, active = true, timer: ReturnType<typeof setInterval> | undefined
watch(() => weather.query, () => { sequence++; locations.value = []; searched.value = false; searching.value = false; locating.value = false; locationError.value = '' })
function openSettings() { settingsOpen.value = true }
async function closeSettings() {
  if (weather.saving || searching.value || locating.value) return
  if (weather.dirty) {
    await weather.save()
    if (weather.dirty || weather.error) return
  }
  settingsOpen.value = false
}
useOverlayDismiss(() => settingsOpen.value, closeSettings)
defineExpose({ openSettings })
async function search() {
  const request = ++sequence
  searching.value = true; searched.value = false; locationError.value = ''; locations.value = []
  try { const values = await searchWeatherLocations(weather.query.trim()); if (active && request === sequence) { locations.value = values; searched.value = true } }
  catch (e) { if (active && request === sequence) locationError.value = weatherErrorMessage(e, '城市搜索失败，请重试') }
  finally { if (active && request === sequence) searching.value = false }
}
function locate() {
  const request = ++sequence
  locationError.value = ''
  if (!navigator.geolocation) { locationError.value = '此浏览器不支持定位，请搜索城市'; return }
  locating.value = true
  navigator.geolocation.getCurrentPosition(position => {
    if (!active || request !== sequence) return
    weather.draft.location = { name: '当前位置', latitude: Math.round(position.coords.latitude * 100) / 100, longitude: Math.round(position.coords.longitude * 100) / 100 }
    locating.value = false
  }, () => {
    if (!active || request !== sequence) return
    locating.value = false; locationError.value = '定位未成功，可允许浏览器定位后重试，或直接搜索城市'
  }, { enableHighAccuracy: false, timeout: 10_000, maximumAge: 300_000 })
}
onMounted(() => { weather.checkFreshness(); if (weather.stale) void weather.refresh(); timer = setInterval(weather.checkFreshness, 60_000) })
onUnmounted(() => { active = false; sequence++; if (timer) clearInterval(timer) })
</script>

<template>
  <section v-if="showPanel" class="weather-panel" :class="{ 'weather-compact': compact, 'weather-toolbar': toolbar, 'weather-glance': glance }" aria-label="天气与卦名设置">
    <template v-if="glance">
      <div class="weather-glance-line" :title="weather.forecastError || weather.error || weather.prefs.location?.name || ''">
        <span class="weather-glance-icon" aria-hidden="true">{{ glanceCondition && !weather.stale && !weather.forecastError ? glanceCondition.icon : '🌡️' }}</span>
        <span role="status">{{ weather.loaded && weather.prefs.location ? `${glanceCity} · ${glanceLabel}` : glanceLabel }}</span>
        <button v-if="weather.error && !weather.loaded" type="button" aria-label="重试读取天气设置" title="重试读取天气设置" @click="weather.load"><i class="ri-refresh-line" /></button>
        <button v-if="weather.loaded && weather.prefs.location && (weather.forecastError || weather.stale)" type="button" aria-label="重试更新天气" title="重试更新天气" @click="weather.refresh"><i class="ri-refresh-line" /></button>
        <button type="button" aria-label="天气设置" title="天气设置" :aria-expanded="settingsOpen" @click="settingsOpen ? closeSettings() : openSettings()"><i class="ri-settings-3-line" /></button>
      </div>
    </template>
    <template v-else>
    <header><strong>天气<span v-if="weather.prefs.location"> · {{ weather.prefs.location.name }}</span></strong><span v-if="!visible">已隐藏</span><button type="button" @click="settingsOpen ? closeSettings() : openSettings()" :aria-expanded="settingsOpen">天气设置</button></header>
    <p v-if="weather.loading">正在读取天气设置…</p>
    <p v-if="weather.error" role="alert">{{ weather.error }} <button v-if="!weather.loaded" @click="weather.load">重新读取</button></p>
    <template v-if="visible && weather.loaded">
      <p v-if="!weather.prefs.location">选择城市或使用当前位置，查看天气和出行提醒。</p>
      <p v-else-if="weather.forecasting">正在更新预报…</p>
      <p v-else-if="weather.forecastError || weather.stale" role="alert" :title="weather.forecastError || '预报已超过半小时，请更新后查看'">{{ weather.forecastError || '预报已超过半小时，请更新后查看' }} <button @click="weather.refresh">重试更新</button></p>
      <template v-else-if="weather.forecast">
        <div v-if="compact" class="weather-two-days">
          <span v-for="day in twoDays" :key="day.date" :title="day.forecast ? weatherAdvice(day.forecast) : ''">
            <b>{{ day.label }}</b>
            <template v-if="day.forecast">{{ weatherName(day.forecast.code) }} · {{ weatherTemperature(day.forecast) }}</template>
            <template v-else>暂无预报</template>
          </span>
          <button type="button" aria-label="刷新天气" title="刷新天气" @click="weather.refresh"><i class="ri-refresh-line" /></button>
          <a href="https://open-meteo.com/" target="_blank" rel="noopener noreferrer" :title="`Open-Meteo · 北京时间 · ${new Date(weather.forecast.fetchedAt).toLocaleString('zh-CN')} 获取`">Open-Meteo</a>
        </div>
        <template v-else>
        <div v-if="today" class="weather-today"><b>{{ date }} · {{ weatherName(today.code) }} · {{ weatherTemperature(today) }}</b><span>降水概率 {{ today.rain === null ? '未知' : `${today.rain}%` }} · 最大风速 {{ today.wind === null ? '未知' : `${today.wind} km/h` }}</span></div>
        <p v-else>该日期不在当前预报范围内。</p>
        <p v-if="advice" class="weather-advice" role="status">出行提醒：{{ advice }}。</p>
        <div class="weather-week"><div v-for="day in weather.days" :key="day.date"><time>{{ day.date.slice(5) }}</time><span>{{ weatherName(day.code) }}</span><span>{{ weatherTemperature(day) }}</span></div></div>
        <small><a href="https://open-meteo.com/" target="_blank" rel="noopener noreferrer">Open-Meteo</a> · 未来 7 日预报（北京时间） · {{ new Date(weather.forecast.fetchedAt).toLocaleString('zh-CN') }} 获取 <button @click="weather.refresh">刷新天气</button></small>
        </template>
      </template>
    </template>
    </template>
  </section>
  <component :is="compact ? Modal : 'div'" v-if="settingsOpen" :open="settingsOpen" :footer="null" title="天气设置" @cancel="closeSettings">
    <form class="weather-settings" @submit.prevent="weather.save">
      <fieldset :disabled="weather.saving || !weather.loaded">
        <legend>位置与显示</legend>
        <p>当前选择：{{ weather.draft.location?.name || '尚未选择城市' }} <button type="button" v-if="weather.draft.location" @click="weather.draft.location = null">清除位置</button></p>
        <div class="weather-search"><label>城市<input v-model="weather.query" maxlength="80" placeholder="例如：武汉" @keydown.enter.prevent="search" /></label><button type="button" :disabled="weather.query.trim().length < 2 || searching || locating" @click="search">{{ searching ? '搜索中…' : '搜索城市' }}</button><button type="button" :disabled="locating || searching" @click="locate">{{ locating ? '定位中…' : '使用当前位置' }}</button></div>
        <small>定位需浏览器授权；仅用约公里精度的位置获取天气。城市名称来自 <a href="https://www.geonames.org/" target="_blank" rel="noopener noreferrer">GeoNames</a>。</small>
        <p v-if="locationError" role="alert">{{ locationError }}</p><p v-else-if="searched && !locations.length">未找到城市，可换用拼音或更完整的城市名。</p>
        <div class="weather-results"><button v-for="location in locations" :key="`${location.latitude}:${location.longitude}`" type="button" @click="weather.draft.location = location">{{ location.name }}</button></div>
        <div class="weather-switches"><label><input type="checkbox" v-model="weather.draft.rhythmVisible" />今日节奏显示天气</label><label><input type="checkbox" v-model="weather.draft.calendarVisible" />日历显示天气</label><label><input type="checkbox" v-model="weather.draft.readingsVisible" />日历显示已保存卦名</label></div>
        <button type="submit" :disabled="!weather.dirty || searching || locating">{{ weather.saving ? '保存中…' : weather.error ? '重试保存天气设置' : '保存天气设置' }}</button><button type="button" :disabled="!weather.dirty" @click="weather.resetDraft">恢复已保存设置</button>
      </fieldset>
    </form>
    <p v-if="weather.message" role="status">{{ weather.message }}</p>
    <p v-if="compact && weather.error" role="alert">{{ weather.error }}</p>
  </component>
</template>

<style scoped>
.weather-search label{white-space:nowrap}.weather-search input{flex:1}
.weather-compact{padding:9px 0 0!important;margin:12px 0 0!important;border:0!important;border-top:1px solid #ffffff30!important;border-radius:0!important;background:transparent!important;color:inherit!important;font-size:12px}
.weather-compact header{gap:8px}.weather-compact header strong{flex:initial}.weather-compact header button,.weather-compact .weather-two-days button{background:transparent;color:inherit;border-color:#ffffff40;font-size:11px;padding:2px 6px}
.weather-compact p{margin:5px 0}.weather-two-days{display:flex;flex-wrap:wrap;align-items:center;gap:6px 14px;margin-top:6px}.weather-two-days>span{display:flex;gap:6px;flex-wrap:wrap}.weather-two-days a{color:inherit;font-size:10px;opacity:.75}
.weather-panel{padding:16px 18px;border:1px solid var(--wao-line);border-radius:14px;background:var(--wao-bg-card);margin:14px 0;min-width:0;font-size:13px;color:var(--wao-text)}
header{display:flex;align-items:center;gap:10px;flex-wrap:wrap}header strong{flex:1;min-width:0;overflow-wrap:anywhere}button{border:1px solid var(--wao-line-control);background:var(--wao-bg-card);color:inherit;border-radius:7px;padding:5px 9px;cursor:pointer}button:disabled{opacity:.55;cursor:default}p{margin:10px 0;line-height:1.7}small{display:block;color:var(--wao-text-3);line-height:1.7}a{color:var(--wao-primary-text)}.weather-today{display:flex;gap:8px 20px;flex-wrap:wrap;margin:12px 0}.weather-advice{padding:9px 12px;border-radius:8px;background:var(--wao-warning-bg);color:var(--wao-warning-strong)}.weather-week{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:6px;margin:12px 0}.weather-week>div{display:flex;flex-direction:column;gap:5px;text-align:center;padding:8px 3px;background:var(--wao-bg-sub);border-radius:8px}.weather-settings{margin-top:12px}fieldset{border:1px solid var(--wao-line);border-radius:10px;padding:12px;min-width:0}.weather-search,.weather-results,.weather-switches{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0}.weather-search label{display:flex;align-items:center;gap:8px;flex:1;min-width:170px}input:not([type=checkbox]){border:1px solid var(--wao-line-control);border-radius:7px;padding:6px 9px;min-width:0;width:100%;background:var(--wao-bg-card);color:inherit}.weather-switches label{display:flex;align-items:center;gap:5px}.weather-results button{text-align:left;overflow-wrap:anywhere}fieldset>button{margin:4px 8px 0 0}@media(max-width:640px){.weather-panel{padding:12px}.weather-week{grid-template-columns:repeat(3,minmax(0,1fr))}.weather-switches{flex-direction:column}.weather-search label{flex-basis:100%}}
.weather-toolbar.weather-compact { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 14px; padding: 6px 0 0!important; margin: 0!important; border-top: 1px solid var(--wao-line)!important; }
.weather-toolbar .weather-two-days { margin: 0; }
.weather-toolbar header button, .weather-toolbar .weather-two-days button { border-color: var(--wao-line-control); }
.weather-toolbar header strong { font-weight: 500; }
.weather-glance.weather-toolbar.weather-compact{border:0!important;padding:0!important;gap:0;justify-content:flex-end;max-width:100%}
.weather-glance-line{display:flex;align-items:center;gap:5px;min-height:32px;min-width:0;white-space:nowrap;font-size:12px;color:var(--wao-text-2)}
.weather-glance-line [role="status"]{min-width:0;overflow:hidden;text-overflow:ellipsis}
.weather-glance-icon{font-size:17px;line-height:1}
.weather-glance-line button{padding:3px;border:0;background:transparent;color:var(--wao-text-3);font-size:13px}
.weather-glance-line button:hover,.weather-glance-line button:focus-visible{color:var(--wao-primary-text)}
@media(max-width:900px){.weather-glance.weather-toolbar.weather-compact{justify-content:flex-start;width:auto}}
</style>
