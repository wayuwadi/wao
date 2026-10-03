export const TODO_DUE_GROUPS = ['逾期', '今天到期', '本周到期', '以后到期', '无截止日期'] as const

/** 周一到周日为一周，只比较日期，不把执行安排当成截止。 */
export function todoDueGroup(due: string | null | undefined, today: string): typeof TODO_DUE_GROUPS[number] {
  if (!due) return '无截止日期'
  if (due < today) return '逾期'
  if (due === today) return '今天到期'
  const end = new Date(`${today}T00:00:00Z`)
  end.setUTCDate(end.getUTCDate() + 7 - (end.getUTCDay() || 7))
  return due <= end.toISOString().slice(0, 10) ? '本周到期' : '以后到期'
}
