/** 活动 / 评论时间戳的记录与展示。 */

/** 记录一个时间点。存 ISO 字符串，展示时再转成相对描述。 */
export const nowIso = () => new Date().toISOString()

const pad = (n: number) => String(n).padStart(2, '0')
const hhmm = (d: Date) => `${pad(d.getHours())}:${pad(d.getMinutes())}`
const sameDay = (a: Date, b: Date) =>
  a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()

/**
 * 把存下来的时间戳转成人读的相对描述。
 *
 * <p>历史数据与种子数据里存的是「昨天 21:40」这种现成文案，不是时间戳 ——
 * 解析不出日期的值原样返回，避免把它们显示成 Invalid Date。
 *
 * @param value 时间戳（ISO 字符串）或历史遗留的现成文案
 * @param now   当前时间，测试时可注入
 * @return 展示文案，如「刚刚」「12 分钟前」「今天 14:22」「7月24日 09:15」
 */
export function relativeTime(value: string, now: Date = new Date()): string {
  const d = parse(value)
  if (!d) return value

  const diffMs = now.getTime() - d.getTime()
  // 时钟漂移或未来时间：不显示「-3 分钟前」这种东西
  if (diffMs < 0) return `${d.getMonth() + 1}月${d.getDate()}日 ${hhmm(d)}`

  const min = Math.floor(diffMs / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min} 分钟前`

  if (sameDay(d, now)) return `今天 ${hhmm(d)}`

  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (sameDay(d, yesterday)) return `昨天 ${hhmm(d)}`

  if (d.getFullYear() === now.getFullYear()) return `${d.getMonth() + 1}月${d.getDate()}日 ${hhmm(d)}`
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
}

/** 只取时分（HH:mm），用于「自动保存成功 14:32」这种就近发生的提示。 */
export function clockTime(value: string): string {
  const d = parse(value)
  return d ? hhmm(d) : ''
}

/** 悬停时显示的完整时间；不是时间戳则返回空（不加 title）。 */
export function absoluteTime(value: string): string {
  const d = parse(value)
  if (!d) return ''
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 ${hhmm(d)}:${pad(d.getSeconds())}`
}

/** 只接受 ISO 8601 形状的字符串，避免把「昨天 21:40」喂给 Date 得到意外结果。 */
function parse(value: string): Date | null {
  if (!value || !/^\d{4}-\d{2}-\d{2}T/.test(value)) return null
  const d = new Date(value)
  return Number.isNaN(d.getTime()) ? null : d
}
