/** SSE only invalidates; the store's normal reads keep authorization and draft protection. */
export function connectTaskChanges(refresh: () => void) {
  if (typeof EventSource === 'undefined') return () => undefined
  const stream = new EventSource('/api/task-changes', { withCredentials: true })
  let timer: ReturnType<typeof setTimeout> | undefined
  const changed = () => {
    if (timer) return
    timer = setTimeout(() => { timer = undefined; refresh() }, 150)
  }
  stream.addEventListener('changed', changed)
  // Recover writes missed while reconnecting; ordinary polling remains the fallback.
  stream.addEventListener('ready', changed)
  return () => { stream.close(); clearTimeout(timer) }
}
