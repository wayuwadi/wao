import type { WeatherDay } from '@/api/weather'

export type LivingSky = 'unknown' | 'sun' | 'cloud' | 'rain' | 'snow' | 'fog' | 'storm'
export type LivingClothes = 'plain' | 'light' | 'jacket' | 'winter' | 'raincoat'
export interface LivingWeather {
  sky: LivingSky
  windy: boolean
  clothes: LivingClothes
  scarf: boolean
  umbrella: boolean
  advice: string
}
export const plainWeather: LivingWeather = {
  sky: 'unknown',
  windy: false,
  clothes: 'plain',
  scarf: false,
  umbrella: false,
  advice: '',
}
const finite = (value: number | null) => value !== null && Number.isFinite(value)

/** 每日预报的视觉解释；未知数据不能被季节或随机值补成已知天气。 */
export function livingWeather(day: WeatherDay | undefined, reliable: boolean): LivingWeather {
  if (!reliable || !day || day.code === null) return { ...plainWeather }
  const code = day.code
  const sky: LivingSky =
    code === 0
      ? 'sun'
      : [1, 2, 3].includes(code)
        ? 'cloud'
        : [45, 48].includes(code)
          ? 'fog'
          : [71, 73, 75, 77, 85, 86].includes(code)
            ? 'snow'
            : [95, 96, 99].includes(code)
              ? 'storm'
              : [51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82].includes(code)
                ? 'rain'
                : 'unknown'
  if (sky === 'unknown') return { ...plainWeather }
  const windy = finite(day.wind) && day.wind! >= 28
  const cold = (finite(day.low) && day.low! <= 5) || (finite(day.high) && day.high! <= 10)
  let clothes: LivingClothes =
    !finite(day.low) || !finite(day.high)
      ? 'plain'
      : cold
        ? 'winter'
        : day.high! <= 20 || day.low! < 14
          ? 'jacket'
          : 'light'
  const wet = sky === 'rain' || sky === 'storm'
  if (wet) clothes = 'raincoat'
  const scarf = cold || (windy && finite(day.high) && day.high! < 22)
  const advice = wet
    ? '今天预报有雨，换件雨衣，出门记得带雨具。'
    : clothes === 'winter'
      ? '今天预报偏冷，我们加了厚外套，出门也记得保暖。'
      : clothes === 'jacket'
        ? '今天早晚偏凉，我们加了薄外套。'
        : clothes === 'light'
          ? '今天穿轻薄一点，出门前再看一眼温度。'
          : ''
  return { sky, windy, clothes, scarf, umbrella: sky === 'rain' && !windy, advice }
}

export interface LivingLine {
  person: number
  text: string
}
export function livingConversation(weather: LivingWeather, variant: number): LivingLine[] {
  const lines: Record<LivingSky, LivingLine[]> = {
    sun: [
      { person: 0, text: '今天预报有阳光，适合把窗帘打开。' },
      { person: 2, text: '墨镜已经戴好了，就等你出门。' },
      { person: 1, text: '先记下那个念头，再去走走。' },
    ],
    cloud: [
      { person: 1, text: '今天预报云多，适合坐着发一会儿呆。' },
      { person: 3, text: '我给你留个位置。' },
      { person: 2, text: '那我负责把今天过得亮一点。' },
    ],
    rain: [
      { person: 3, text: '今天预报有雨，雨具备好了。' },
      { person: 0, text: '往这边挤一挤，给你留点地方。' },
      { person: 1, text: '有个想法的话，先写一句也好。' },
    ],
    snow: [
      { person: 1, text: '今天预报有雪，先穿暖和一点。' },
      { person: 2, text: '这件外套像一床可以走动的被子。' },
      { person: 3, text: '暖和了，再慢慢玩。' },
    ],
    fog: [
      { person: 0, text: '今天预报有雾，远处像没画完。' },
      { person: 1, text: '出门慢一点，想法倒是可以慢慢写。' },
    ],
    storm: [
      { person: 1, text: '今天预报有雷暴，出门前留意当地预警。' },
      { person: 3, text: '我们先安静陪你待一会儿。' },
    ],
    unknown: [
      { person: 0, text: '今天的天气还没读到，我们先待会儿。' },
      { person: 1, text: '想到什么，随手写一句就好。' },
    ],
  }
  if (variant % 3 === 1 && weather.windy)
    return [
      { person: 0, text: '预报说有风，谁帮我按住帽子？' },
      { person: 3, text: '站稳了，你放心。' },
      { person: 2, text: '把烦恼也顺便吹走吧。' },
    ]
  if (variant % 3 === 2 && weather.advice)
    return [
      { person: 1, text: weather.advice },
      { person: 2, text: '穿舒服一点，今天也慢慢来。' },
    ]
  return lines[weather.sky]
}
