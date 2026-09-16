/** Compare local calendar days; creation time, not later edits, defines recent notes. */
export function matchesNoteDate(created: string, filter: 'all' | 'today' | 'yesterday', now = new Date()): boolean {
  if (filter === 'all') return true
  const day = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  if (filter === 'yesterday') day.setDate(day.getDate() - 1)
  const end = new Date(day)
  end.setDate(end.getDate() + 1)
  const timestamp = Date.parse(created)
  return timestamp >= day.getTime() && timestamp < end.getTime()
}
