import { http, type ApiResponse } from './http'

export type PrincipleCategory =
  'RED_LINE' | 'MUST' | 'MORE' | 'LESS' | 'ALLOWED' | 'AVOID' | 'ADVICE'
export type PrincipleEvidence = 'MANUAL' | 'SCREEN_TIME'
export type PrincipleConsent = 'ACCEPTED' | 'PENDING' | 'DECLINED' | 'NOT_TARGET'
export type PrincipleJudgment =
  | 'ADVICE'
  | 'PAUSED'
  | 'MANAGED_ONLY'
  | 'PENDING_CONSENT'
  | 'WAITING_DATA'
  | 'COMPLETED'
  | 'EXCEPTION'
  | 'NEEDS_ATTENTION'
  | 'RECORD_DUE'
  | 'PENDING'
export interface PersonalPrinciple {
  id: string
  ownerMemberId: string
  title: string
  category: PrincipleCategory
  triggerText: string
  desiredAction: string
  exceptionText: string
  reminderTime: string | null
  evidenceSource: PrincipleEvidence
  paused: boolean
  version: number
  updatedAt: string
  targets: Array<{ memberId: string; consent: PrincipleConsent }>
  myConsent: PrincipleConsent
  selectedDate: string
  myCheckin: {
    status: 'DONE' | 'NEEDS_WORK' | 'EXCEPTION'
    note: string
    principleVersion: number
    updatedAt: string
  } | null
  judgment: PrincipleJudgment
  judgmentReason: string
}
export interface SavePersonalPrinciple {
  title: string
  category: PrincipleCategory
  targetMemberIds: string[]
  triggerText: string
  desiredAction: string
  exceptionText: string
  reminderTime: string | null
  evidenceSource: PrincipleEvidence
  paused: boolean
  version?: number
}

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}
export const fetchPersonalPrinciples = (date?: string) =>
  data<PersonalPrinciple[]>(http.get('/api/personal-principles', { params: date ? { date } : {} }))
export const createPersonalPrinciple = (body: SavePersonalPrinciple) =>
  data<PersonalPrinciple>(http.post('/api/personal-principles', body))
export const updatePersonalPrinciple = (id: string, body: SavePersonalPrinciple) =>
  data<PersonalPrinciple>(http.put(`/api/personal-principles/${id}`, body))
export const setPrincipleConsent = (id: string, consent: 'ACCEPTED' | 'DECLINED') =>
  data<PersonalPrinciple>(http.put(`/api/personal-principles/${id}/consent`, { consent }))
export const savePrincipleCheckin = (
  id: string,
  date: string,
  status: 'DONE' | 'NEEDS_WORK' | 'EXCEPTION',
  note: string,
) =>
  data<PersonalPrinciple>(
    http.put(`/api/personal-principles/${id}/checkins/${date}`, { status, note }),
  )
export const fetchPrincipleRevisions = (id: string) =>
  data<string[]>(http.get(`/api/personal-principles/${id}/revisions`))
