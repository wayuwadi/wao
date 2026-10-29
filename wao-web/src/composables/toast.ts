import { reactive } from 'vue'

export interface ToastItem {
  id: number
  text: string
  icon: string
  tone: 'status' | 'error'
  action?: { label: string; run: () => Promise<void> | void }
  busy?: boolean
}
export interface ToastOptions { tone?: ToastItem['tone']; duration?: number; action?: ToastItem['action'] }
const state = reactive({ items: [] as ToastItem[] })
let seq = 0
const timers = new Map<number, { remaining: number; started: number; timer?: ReturnType<typeof setTimeout>; pauses: Set<string> }>()
export function useToastHost() { return state }

export function dismissToast(id: number) {
  const timer = timers.get(id)
  if (timer?.timer !== undefined) clearTimeout(timer.timer)
  timers.delete(id)
  const index = state.items.findIndex(item => item.id === id)
  if (index >= 0) state.items.splice(index, 1)
}
export function clearToasts() { for (const id of [...timers.keys()]) dismissToast(id); state.items.splice(0) }
function schedule(id: number) {
  const timer = timers.get(id)
  if (!timer || timer.pauses.size || timer.remaining <= 0) return
  timer.started = Date.now()
  timer.timer = setTimeout(() => dismissToast(id), timer.remaining)
}
export function pauseToast(id: number, reason: 'pointer' | 'focus', paused: boolean) {
  const timer = timers.get(id)
  if (!timer) return
  if (paused) {
    if (!timer.pauses.size && timer.timer !== undefined) {
      clearTimeout(timer.timer)
      timer.timer = undefined
      timer.remaining = Math.max(1, timer.remaining - (Date.now() - timer.started))
    }
    timer.pauses.add(reason)
  } else {
    timer.pauses.delete(reason)
    if (!timer.pauses.size && timer.timer === undefined) schedule(id)
  }
}
/** Existing error icons opt into persistent alerts; other legacy calls retain their duration. */
export function toast(text: string, icon = 'ri-information-line', options: ToastOptions = {}) {
  const id = ++seq
  const tone = options.tone ?? (['ri-error-warning-line', 'ri-close-circle-line'].includes(icon) ? 'error' : 'status')
  const duration = options.duration ?? (tone === 'error' ? 0 : 2600)
  state.items.push({ id, text, icon, tone, action: options.action })
  if (duration > 0) {
    timers.set(id, { remaining: duration, started: Date.now(), pauses: new Set() })
    schedule(id)
  }
  return id
}

export async function runToastAction(id: number) {
  const item = state.items.find(item => item.id === id)
  if (!item?.action || item.busy) return
  item.busy = true
  pauseToast(id, 'focus', true)
  try { await item.action.run(); dismissToast(id) }
  catch (error) {
    item.text = error instanceof Error ? error.message : '操作未保存，请重试'
    const timer = timers.get(id)
    if (timer?.timer !== undefined) clearTimeout(timer.timer)
    timers.delete(id)
    item.tone = 'error'; item.busy = false
  }
}
