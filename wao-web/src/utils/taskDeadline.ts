/** Calendar-day arithmetic uses UTC components to avoid DST and browser timezone shifts. */
export function shiftDay(day: string, offset: number): string {
  return new Date(Date.parse(`${day}T00:00:00Z`) + offset * 86400000).toISOString().slice(0, 10)
}
export function deadlineInfo(due: string | null | undefined, today: string, completed = false) {
  if (!due) return { label:'设置截止日期', overdue:0 }
  const delta = Math.round((Date.parse(`${due}T00:00:00Z`) - Date.parse(`${today}T00:00:00Z`)) / 86400000)
  const date = `${due.slice(0,4) === today.slice(0,4) ? '' : due.slice(0,4) + '年'}${Number(due.slice(5,7))}月${Number(due.slice(8,10))}日`
  return { label:`${date}${delta === 0 ? '，今天' : delta === 1 ? '，明天' : ''}`, overdue:completed ? 0 : Math.max(0, -delta) }
}
