import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount } from '@vue/test-utils'
import { useWeatherStore, parseWeatherPreferences } from '@/stores/weather'
import { weatherAdvice, weatherName, weatherGlance } from '@/utils/weather'
import WeatherPanel from '@/components/calendar/WeatherPanel.vue'
const api = vi.hoisted(() => ({ fetchPreference: vi.fn(), savePreference: vi.fn(), fetchWeather: vi.fn(), searchWeatherLocations: vi.fn() }))
vi.mock('@/api/favorites', () => api)
vi.mock('@/api/weather', () => api)
vi.mock('@/stores/session', () => ({ useSessionStore: () => ({ currentUser: { id: 'weather-member' } }) }))
const city = { name: '上海', latitude: 31.23, longitude: 121.47 }
const initial = () => ({ location: city, rhythmVisible: true, calendarVisible: true, readingsVisible: true })
const forecast = () => ({ fetchedAt: new Date().toISOString(), days: [{ date: '2026-09-04', code: 0, low: 21, high: 29, rain: 0, wind: 10 }] })
describe('真实天气与成员设置', () => {
  beforeEach(() => { localStorage.clear(); vi.clearAllMocks(); setActivePinia(createPinia()); api.fetchPreference.mockResolvedValue(''); api.savePreference.mockResolvedValue(undefined); api.fetchWeather.mockResolvedValue(forecast()) })
  it('默认武汉，保留已有城市和主动清空设置，真实零值仍可展示', async () => {
    const store = useWeatherStore(); await flushPromises()
    expect(store.prefs.location?.name).toBe('武汉'); expect(api.fetchWeather).toHaveBeenCalled()
    expect(parseWeatherPreferences(JSON.stringify({ location: { ...city, latitude: 91 } })).location?.name).toBe('武汉')
    expect(parseWeatherPreferences(JSON.stringify(initial())).location).toEqual(city)
    expect(parseWeatherPreferences(JSON.stringify({ location: null })).location).toBeNull()
    expect(weatherName(0)).toBe('晴'); expect(weatherName(null)).toBe('天气状况未知')
  })
  it('迟到的设置读取不覆盖本地输入，保存失败仍保留草稿并可重试', async () => {
    let finish!: (value: string) => void
    api.fetchPreference.mockReturnValueOnce(new Promise<string>(resolve => { finish = resolve }))
    const store = useWeatherStore(); store.draft.location = city
    finish(''); await flushPromises(); expect(store.draft.location?.name).toBe('上海')
    api.savePreference.mockRejectedValueOnce(new Error('网络超时'))
    await store.save(); expect(store.error).toBe('网络超时'); expect(store.prefs.location?.name).toBe('武汉')
    expect(localStorage.getItem('wao-weather-draft-v1:weather-member')).toContain('上海')
    await store.save(); expect(store.prefs.location?.name).toBe('上海'); expect(store.message).toBe('天气设置已保存')
    expect(localStorage.getItem('wao-weather-draft-v1:weather-member')).toBeNull()
  })
  it('工具栏开关只保存单项显示偏好，保留其他天气草稿；失败时回滚', async () => {
    const store = useWeatherStore(); await flushPromises()
    store.draft.location = city
    expect(await store.setCalendarVisibility('calendarVisible', false)).toBe(true)
    const calls = vi.mocked(api.savePreference).mock.calls
    const saved = JSON.parse(calls[calls.length - 1]![1] as string)
    expect(saved.location.name).toBe('武汉')
    expect(saved.calendarVisible).toBe(false)
    expect(store.prefs.calendarVisible).toBe(false)
    expect(store.draft.location?.name).toBe('上海')
    expect(store.draft.calendarVisible).toBe(false)
    expect(store.dirty).toBe(true)

    api.savePreference.mockRejectedValueOnce(new Error('保存失败'))
    expect(await store.setCalendarVisibility('readingsVisible', false)).toBe(false)
    expect(store.prefs.readingsVisible).toBe(true)
    expect(store.draft.readingsVisible).toBe(true)
    expect(store.error).toBe('保存失败')
  })
  it('位置切换后旧请求不能覆盖新预报，过期预报不再进入日历', async () => {
    api.fetchPreference.mockResolvedValue(JSON.stringify(initial()))
    let finish!: (value: ReturnType<typeof forecast>) => void
    api.fetchWeather.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    const store = useWeatherStore(); await flushPromises()
    store.draft.location = { name: '北京', latitude: 39.9, longitude: 116.4 }
    const newer = forecast(); newer.days[0]!.high = 30
    api.fetchWeather.mockResolvedValueOnce(newer); await store.save()
    finish(forecast()); await flushPromises(); expect(store.days[0]?.high).toBe(30)
    store.forecast!.fetchedAt = '2000-01-01T00:00:00Z'; store.checkFreshness(); expect(store.days).toEqual([])
  })
  it('按实际雨雪温度产生提醒，未知值不触发低温提示', () => {
    expect(weatherAdvice({ date: '', code: null, low: null, high: null, rain: null, wind: null })).toBe('')
    expect(weatherAdvice({ date: '', code: 95, low: 0, high: 35, rain: 90, wind: 40 })).toContain('雷暴')
    expect(weatherAdvice({ date: '', code: 71, low: -2, high: 1, rain: 30, wind: 4 })).toContain('雨雪结冰')
  })
  it('首页天气独立于日历隐藏设置，只显示短城市、真实天气和图标', async () => {
    api.fetchPreference.mockResolvedValue(JSON.stringify({ ...initial(), location: { ...city, name: '上海 · 上海 · 中国' }, rhythmVisible: false, calendarVisible: false }))
    const today = new Date().toISOString().slice(0, 10)
    api.fetchWeather.mockResolvedValue({ fetchedAt: new Date().toISOString(), days: [{ ...forecast().days[0], date: today, code: 61 }] })
    const wrapper = mount(WeatherPanel, { props: { surface: 'calendar', date: today, toolbar: true, glance: true } })
    await flushPromises()
    expect(api.fetchWeather).toHaveBeenCalled()
    expect(wrapper.get('.weather-glance-line').text()).toContain('🌧️上海 · 有雨')
    expect(wrapper.text()).not.toContain('已隐藏')
    expect(wrapper.text()).not.toContain('中国')
    expect(weatherGlance(null)).toEqual({ icon: '🌡️', text: '天气未知' })
    wrapper.unmount()
  })
  it('拒绝定位时提示手选城市，失败不保存臆测位置', async () => {
    Object.defineProperty(navigator, 'geolocation', { configurable: true, value: { getCurrentPosition: (_success: unknown, fail: () => void) => fail() } })
    const wrapper = mount(WeatherPanel, { props: { surface: 'calendar', date: '2026-09-04' } }); await flushPromises()
    await wrapper.get('header button').trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '使用当前位置')!.trigger('click')
    expect(wrapper.text()).toContain('定位未成功'); expect(api.savePreference).not.toHaveBeenCalled(); wrapper.unmount()
  })
  it('节奏只显示跨年两日摘要，日历保留完整预报', async () => {
    api.fetchPreference.mockResolvedValue(JSON.stringify(initial()))
    api.fetchWeather.mockResolvedValue({ fetchedAt: new Date().toISOString(), days: [
      { ...forecast().days[0], date: '2027-01-02', high: 35 },
      { ...forecast().days[0], date: '2026-12-31', high: 29 },
      { ...forecast().days[0], date: '2027-01-01', high: 30 },
    ] })
    const wrapper = mount(WeatherPanel, { props: { surface: 'rhythm', date: '2026-12-31' } })
    await flushPromises()
    expect(wrapper.get('.weather-two-days').text()).toContain('今日晴 · 21～29℃')
    expect(wrapper.get('.weather-two-days').text()).toContain('明日晴 · 21～30℃')
    expect(wrapper.find('.weather-week').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('35℃')
    await wrapper.setProps({ surface: 'calendar' })
    expect(wrapper.findAll('.weather-week>div')).toHaveLength(3)
    wrapper.unmount()
  })
  it('两日摘要对缺失明日预报明确留空且保留更新失败重试', async () => {
    api.fetchPreference.mockResolvedValue(JSON.stringify(initial()))
    const wrapper = mount(WeatherPanel, { props: { surface: 'rhythm', date: '2026-09-04' } })
    await flushPromises()
    expect(wrapper.text()).toContain('明日暂无预报')
    api.fetchWeather.mockRejectedValueOnce(new Error('预报服务不可用'))
    await wrapper.get('[aria-label="刷新天气"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('预报服务不可用')
    expect(wrapper.text()).toContain('重试更新')
    wrapper.unmount()
  })
})
