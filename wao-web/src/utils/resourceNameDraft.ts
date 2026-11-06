export interface ResourceNameDraft {
  value: string
  base: string
  expectedName: string | null
  revision: string
  savedAt: number
}

const drafts = new Map<string, ResourceNameDraft>()
const MAX_DRAFTS = 256
const MAX_AGE = 7 * 24 * 60 * 60 * 1000
let sequence = 0

export function resourceNameDraftKey(accountId?: string, memberId?: string, fileId?: string) {
  return accountId && memberId && fileId
    ? `wao-resource-name-draft-v1:${[accountId, memberId, fileId].map(encodeURIComponent).join(':')}`
    : ''
}

function valid(value: unknown): value is ResourceNameDraft {
  if (!value || typeof value !== 'object') return false
  const draft = value as Partial<ResourceNameDraft>
  return (
    typeof draft.value === 'string' &&
    draft.value.length <= 255 &&
    typeof draft.base === 'string' &&
    draft.base.length <= 255 &&
    (draft.expectedName === null ||
      (typeof draft.expectedName === 'string' && draft.expectedName.length <= 255)) &&
    typeof draft.revision === 'string' &&
    typeof draft.savedAt === 'number' &&
    Number.isFinite(draft.savedAt) &&
    draft.savedAt <= Date.now() &&
    draft.savedAt >= Date.now() - MAX_AGE
  )
}

export function readResourceNameDraft(key: string): ResourceNameDraft | null {
  if (!key) return null
  const memory = drafts.get(key)
  if (valid(memory)) return memory
  drafts.delete(key)
  try {
    const stored: unknown = JSON.parse(sessionStorage.getItem(key) || 'null')
    if (valid(stored)) return stored
    sessionStorage.removeItem(key)
  } catch {
    /* The current tab can still keep drafts in memory. */
  }
  return null
}

export function saveResourceNameDraft(
  key: string,
  value: string,
  base: string,
  expectedName: string | null,
): ResourceNameDraft | null {
  if (!key) return null
  const draft = {
    value,
    base,
    expectedName,
    revision: `${Date.now()}-${++sequence}`,
    savedAt: Date.now(),
  }
  drafts.delete(key)
  drafts.set(key, draft)
  if (drafts.size > MAX_DRAFTS) drafts.delete(drafts.keys().next().value!)
  try {
    sessionStorage.setItem(key, JSON.stringify(draft))
  } catch {
    /* Memory fallback preserves page navigation. */
  }
  return draft
}

/** A late save response must not erase input from a newly opened editor. */
export function clearResourceNameDraft(key: string, expectedRevision?: string) {
  if (
    !key ||
    (expectedRevision !== undefined && readResourceNameDraft(key)?.revision !== expectedRevision)
  )
    return
  drafts.delete(key)
  try {
    sessionStorage.removeItem(key)
  } catch {
    /* Memory state is already cleared. */
  }
}
