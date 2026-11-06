import type { Priority } from '@/types'
export interface CaptureFields { project: string; pr: Priority; due: string; start: string; startAt: string; endAt: string; tags: string }
export const emptyCapture = (project = '', pr: Priority = 'P2'): CaptureFields => ({ project, pr, due: '', start: '', startAt: '', endAt: '', tags: '' })
const pad = (v: number) => String(v).padStart(2, '0')
const iso = (v: Date) => `${v.getUTCFullYear()}-${pad(v.getUTCMonth() + 1)}-${pad(v.getUTCDate())}`
/** Conservative, deterministic recognition. Nothing is removed until the user applies the preview. */
export function recognizeTaskTime(text: string, today: string) {
  const match = /(今天|明天|后天|下周[一二三四五六日天]|周[一二三四五六日天]|\d{4}-\d{1,2}-\d{1,2}|\d{1,2}月\d{1,2}[日号])(?:\s*(上午|下午|晚上|中午)?\s*([零一二三四五六七八九十两\d]{1,3})(?:点|:|：)(半|[零一二三四五六七八九十两\d]{1,3}分?)?)?/.exec(text)
  if (!match) return null
  const day = new Date(`${today}T00:00:00Z`), token = match[1]!
  if (['今天','明天','后天'].includes(token)) day.setUTCDate(day.getUTCDate() + ['今天','明天','后天'].indexOf(token))
  else if (token.includes('周')) {
    const weekday = (token.endsWith('天')?7:'一二三四五六日'.indexOf(token.slice(-1))+1)
    const current = day.getUTCDay() || 7
    day.setUTCDate(day.getUTCDate() + (token.startsWith('下周') ? 7 + weekday - current : (weekday - current + 7) % 7))
  } else {
    const parts = token.match(/\d+/g)!.map(Number)
    const year = parts.length === 3 ? parts[0]! : day.getUTCFullYear(), month = parts.length === 3 ? parts[1]! : parts[0]!, date = parts[parts.length-1]!
    day.setUTCFullYear(year, month - 1, date)
    if (day.getUTCFullYear() !== year || day.getUTCMonth() !== month - 1 || day.getUTCDate() !== date) return null
  }
  let startAt = '', endAt = ''
  if (match[3]) {
    const number = (raw: string) => /^\d+$/.test(raw) ? Number(raw) : raw === '两' ? 2 : raw.includes('十') ? (raw[0] === '十' ? 1 : '零一二三四五六七八九'.indexOf(raw[0]!)) * 10 + (raw.endsWith('十') ? 0 : '零一二三四五六七八九'.indexOf(raw[raw.length-1]!)) : '零一二三四五六七八九'.indexOf(raw)
    let hour = number(match[3]); const minute = match[4] === '半' ? 30 : number((match[4] || '0').replace('分',''))
    if (['下午','晚上','中午'].includes(match[2] || '') && hour < 12) hour += 12
    if (match[2] === '上午' && hour === 12) hour = 0
    if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null
    startAt = `${iso(day)}T${pad(hour)}:${pad(minute)}`
    const end = new Date(`${startAt}:00Z`); end.setUTCMinutes(end.getUTCMinutes() + 60)
    endAt = `${iso(end)}T${pad(end.getUTCHours())}:${pad(end.getUTCMinutes())}`
  }
  return { start: iso(day), startAt, endAt, matched: match[0], title: (text.slice(0, match.index) + text.slice(match.index + match[0].length)).trim() }
}
export function capturePayload(value: CaptureFields) {
  if (value.startAt && (!value.endAt || value.endAt <= value.startAt)) throw new Error('结束时间必须晚于开始时间')
  const tags = [...new Set(value.tags.split(/[,，]/).map(v => v.trim()).filter(Boolean))]
  if (tags.length > 20 || tags.some(tag => tag.length > 40)) throw new Error('最多 20 个标签，每个不超过 40 字')
  return { project: value.project, pr: value.pr, due: value.due, start: value.startAt ? value.startAt.slice(0,10) : value.start, startAt: value.startAt || null, endAt: value.startAt ? value.endAt : null }
}
