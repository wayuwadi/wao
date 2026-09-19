import { Marked } from 'marked'
import type { SaveActionPlan } from '@/api/actionPlans'

export interface ReviewActionSource {
  id: string
  periodLabel: string
  text: string
}
export interface ReviewActionDraft {
  source: ReviewActionSource
  form?: SaveActionPlan
}
const parser = new Marked()
export function reviewActionSuggestions(text: string): string[] {
  const items = parser.lexer(text).flatMap(token => token.type === 'list'
    ? token.items.map((item: { text: string }) => item.text)
    : token.type === 'paragraph' ? [token.raw] : [])
  return (items.length ? items : [text]).map(item => item.trim()).filter(Boolean)
}
export function reviewActionDraftKey(memberId: string, id: string) {
  if (!memberId || !id || id.length > 300) throw new Error('缺少有效的账号或草稿标识')
  return `wao-review-action-v1:${encodeURIComponent(memberId)}:${encodeURIComponent(id)}`
}
export function readReviewActionDraft(memberId: string, id: string): ReviewActionDraft | null {
  const raw = localStorage.getItem(reviewActionDraftKey(memberId, id))
  if (!raw) return null
  const draft = JSON.parse(raw) as ReviewActionDraft
  if (!draft?.source || draft.source.id !== id || typeof draft.source.text !== 'string' || typeof draft.source.periodLabel !== 'string') throw new Error('回顾草稿格式无法读取')
  if (draft.form) {
    const form = draft.form
    if (typeof form.title !== 'string' || typeof form.vision !== 'string' || typeof form.startDate !== 'string' || typeof form.targetDate !== 'string'
      || typeof form.reminderEnabled !== 'boolean' || !(form.reminderTime === null || typeof form.reminderTime === 'string')
      || !Array.isArray(form.items) || !form.items.length || form.items.some(item => !item || typeof item.title !== 'string' || !Array.isArray(item.weekdays) || item.weekdays.some(day => !Number.isInteger(day) || day < 1 || day > 7))) throw new Error('行动草稿格式无法读取')
  }
  return draft
}
export function prepareReviewActionDraft(memberId: string, source: ReviewActionSource) {
  if (!readReviewActionDraft(memberId, source.id)) {
    localStorage.setItem(reviewActionDraftKey(memberId, source.id), JSON.stringify({ source }))
  }
}
export function writeReviewActionDraft(memberId: string, draft: ReviewActionDraft) {
  localStorage.setItem(reviewActionDraftKey(memberId, draft.source.id), JSON.stringify(draft))
}
