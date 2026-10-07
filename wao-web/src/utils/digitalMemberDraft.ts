import type { DigitalSubjectInput } from '@/api/digitalMembers'
export interface DigitalMemberDraft {
  form: DigitalSubjectInput
  expectedRevision: number
  look: { name: string; style: string; assetIds: string[]; portraitAssetId: string | null; lookId?: string; expectedRevision?: number }
  generation?: { requestId: string; runId?: string }
}
export const digitalDraftKey = (member: string, subject: string) =>
  `wao-digital-member-draft-v1:${member}:${subject}`
export function readDigitalDraft(member: string, subject: string): DigitalMemberDraft | null {
  if (!member) return null
  try {
    const value = JSON.parse(
      localStorage.getItem(digitalDraftKey(member, subject)) || 'null',
    ) as DigitalMemberDraft | null
    return value?.form &&
      ['human', 'pet'].includes(value.form.kind) &&
      ['male', 'female', 'unknown'].includes(value.form.gender) &&
      typeof value.expectedRevision === 'number' &&
      value.look &&
      Array.isArray(value.look.assetIds)
      ? value
      : null
  } catch {
    return null
  }
}
export function rememberDigitalDraft(
  member: string,
  subject: string,
  draft: DigitalMemberDraft | null,
): boolean {
  if (!member) return false
  try {
    if (draft) localStorage.setItem(digitalDraftKey(member, subject), JSON.stringify(draft))
    else localStorage.removeItem(digitalDraftKey(member, subject))
    return true
  } catch {
    return false
  }
}
