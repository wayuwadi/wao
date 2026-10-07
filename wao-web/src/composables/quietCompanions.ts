import { ref, shallowRef } from 'vue'
import { randomCompanion, type CompanionId } from '@/utils/companionIdentity'

export type CompanionKind = 'note' | 'task' | 'empty'
export type CompanionTicket = { epoch: number; at: number; anchor: DOMRect }
export const companionQuiet = ref(false)
export const companionEditing = ref(false)
export const companionMember = ref('')
export const companionResponse = shallowRef<{ kind: CompanionKind; ticket: CompanionTicket; identity?: CompanionId } | null>(null)
const blockers = new Set<string>()
const last = new Map<CompanionKind, number>()
let epoch = 0
let member = ''
let interaction: { element: Element; at: number } | undefined
let emptySeen = false

export function resetCompanionContext(user?: string) {
  epoch++
  interaction = undefined
  companionResponse.value = null
  if (user !== undefined && user !== member) {
    member = user
    companionMember.value = user
    last.clear()
    emptySeen = false
    try {
      companionQuiet.value = sessionStorage.getItem(`wao-companion-quiet:${member}`) === 'true'
      emptySeen = sessionStorage.getItem(`wao-companion-empty:${member}`) === 'true'
    } catch { companionQuiet.value = false }
  }
}
export function setCompanionQuiet(value: boolean) {
  companionQuiet.value = value
  resetCompanionContext()
  try { sessionStorage.setItem(`wao-companion-quiet:${member}`, String(value)) } catch { /* 当前标签页仍生效。 */ }
}
export function blockCompanions(key: string, value: boolean) {
  if (value) { blockers.add(key); companionResponse.value = null }
  else blockers.delete(key)
}
export function cancelCompanionResponse(kind: CompanionKind) {
  if (companionResponse.value?.kind === kind) companionResponse.value = null
}
export function rememberCompanionInteraction(event: Event) {
  if (event.target instanceof Element) interaction = {
    element: event.target.closest('[data-companion-action]')?.querySelector('[data-companion-anchor]') ?? event.target,
    at: Date.now(),
  }
}
/** 只接受当前用户操作；路由切换和过期请求不能在另一个页面补播。 */
export function captureCompanionTicket(element?: Element | null): CompanionTicket | undefined {
  const anchor = element ?? (interaction && Date.now() - interaction.at < 1000 ? interaction.element : undefined)
  if (!anchor?.isConnected || companionQuiet.value || document.hidden) return
  const rect = anchor.getBoundingClientRect()
  if (!rect.width || !rect.height) return
  return { epoch, at: Date.now(), anchor: rect }
}
export function editingCompanionContent() {
  return !!document.activeElement?.closest('input,textarea,[contenteditable="true"],[role="textbox"]')
}
/** 无待播队列；编辑、后台、版本面板和过期响应直接丢弃。 */
export function respondWithCompanion(kind: CompanionKind, ticket?: CompanionTicket) {
  const now = Date.now()
  if (!ticket || ticket.epoch !== epoch || now - ticket.at > 5000 || companionQuiet.value
    || blockers.size || document.hidden || editingCompanionContent() || companionResponse.value
    || now - (last.get(kind) ?? -Infinity) < 30_000 || kind === 'empty' && emptySeen) return false
  last.set(kind, now)
  if (kind === 'empty') {
    emptySeen = true
    try { sessionStorage.setItem(`wao-companion-empty:${member}`, 'true') } catch { /* 当前页面内仍去重。 */ }
  }
  companionResponse.value = { kind, ticket, identity: randomCompanion() }
  return true
}
