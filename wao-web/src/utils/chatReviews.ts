const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/
const DAY_MS = 86_400_000
const OFFSET_SUFFIX = /(?:Z|[+-]\d{2}:\d{2})$/i
export const MIN_CHAT_REVIEW_DATE = '1970-01-01'

/** 新接口返回 RFC 3339 offset；兼容旧的无 offset 值时显式按上海时区解释。 */
export function parseShanghaiDateTime(value: string): Date {
  return new Date(OFFSET_SUFFIX.test(value) ? value : `${value}+08:00`)
}

/** 上海时区的自然日，不受浏览器所在时区影响。 */
export function shanghaiDate(now = new Date()): string {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now)
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}`
}

function dayNumber(value: string): number | null {
  const match = DATE_PATTERN.exec(value)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const result = Date.UTC(year, month - 1, day)
  const roundTrip = new Date(result).toISOString().slice(0, 10)
  return roundTrip === value ? result / DAY_MS : null
}

/** 按自然日偏移，避免本地时区和夏令时导致跨日。 */
export function shiftDate(value: string, days: number): string {
  const current = dayNumber(value)
  if (current === null) return value
  return new Date((current + days) * DAY_MS).toISOString().slice(0, 10)
}

/** 首尾都计入的自然日数；日期非法时返回 0。 */
export function inclusiveDays(startDate: string, endDate: string): number {
  const start = dayNumber(startDate)
  const end = dayNumber(endDate)
  if (start === null || end === null || end < start) return 0
  return end - start + 1
}

export function defaultChatReviewRange(now = new Date()) {
  const endDate = shanghaiDate(now)
  return { startDate: shiftDate(endDate, -6), endDate }
}

/** 返回可直接展示的日期范围错误；空字符串表示通过。 */
export function chatReviewRangeError(
  startDate: string,
  endDate: string,
  today = shanghaiDate(),
): string {
  if (!startDate || !endDate) return '请选择完整的起止日期'
  const start = dayNumber(startDate)
  const end = dayNumber(endDate)
  const current = dayNumber(today)
  if (start === null || end === null || current === null) return '日期格式不正确'
  const minimum = dayNumber(MIN_CHAT_REVIEW_DATE)!
  if (start < minimum || end < minimum) return `日期不能早于 ${MIN_CHAT_REVIEW_DATE}`
  if (start > end) return '开始日期不能晚于结束日期'
  if (end > current) return '结束日期不能晚于上海时区的今天'
  if (end - start + 1 > 31) return '单次复盘最多选择 31 个自然日'
  return ''
}
