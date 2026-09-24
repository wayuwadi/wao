import type { WeatherDay } from '@/api/weather'
export function weatherErrorMessage(error: unknown, fallback: string) {
  if (!(error instanceof Error)) return fallback
  return /timeout|network error/i.test(error.message) ? '连接暂时没有响应，已保留当前设置，请稍后重试' : error.message
}
export function weatherName(code: number | null) {
  if (code === null) return '天气状况未知'
  if (code === 0) return '晴'
  if ([1, 2].includes(code)) return '多云'
  if (code === 3) return '阴'
  if ([45, 48].includes(code)) return '雾'
  if ([51, 53, 55].includes(code)) return '毛毛雨'
  if ([56, 57, 66, 67].includes(code)) return '冻雨'
  if ([61, 63, 65, 80, 81, 82].includes(code)) return '雨'
  if ([71, 73, 75, 77, 85, 86].includes(code)) return '雪'
  if ([95, 96, 99].includes(code)) return '雷暴'
  return '天气状况未知'
}
/** 首页只展示预报代码能确定的天气，不把未知状态写成晴或有雨。 */
export function weatherGlance(code: number | null): { icon: string; text: string } {
  const name = weatherName(code)
  if (name === '晴') return { icon: '☀️', text: '晴' }
  if (name === '多云') return { icon: '⛅', text: '多云' }
  if (name === '阴') return { icon: '☁️', text: '阴' }
  if (name === '雾') return { icon: '🌫️', text: '有雾' }
  if (name === '毛毛雨') return { icon: '🌦️', text: '有小雨' }
  if (name === '雨' || name === '冻雨') return { icon: '🌧️', text: name === '雨' ? '有雨' : '有冻雨' }
  if (name === '雪') return { icon: '🌨️', text: '有雪' }
  if (name === '雷暴') return { icon: '⛈️', text: '有雷暴' }
  return { icon: '🌡️', text: '天气未知' }
}
export function weatherAdvice(day: WeatherDay) {
  const name = weatherName(day.code), hints: string[] = []
  if (name === '雷暴') hints.push('有雷暴，留意当地预警并调整户外安排')
  else if (name.includes('雨') || (day.rain !== null && day.rain >= 60)) hints.push('出门带伞，留意路面湿滑')
  if (name === '雪' || name === '冻雨') hints.push('有雨雪结冰风险，出行预留时间')
  if (name === '雾') hints.push('有雾，留意能见度')
  if (day.high !== null && day.high >= 35) hints.push('天气炎热，注意补水防晒')
  if (day.low !== null && day.low <= 0) hints.push('气温较低，注意保暖')
  if (day.wind !== null && day.wind >= 39) hints.push('风力较大，留意户外物品')
  return hints.join('；')
}
export function weatherTemperature(day: WeatherDay) {
  const value = (n: number | null) => n === null ? '—' : Math.round(n)
  return `${value(day.low)}～${value(day.high)}℃`
}
