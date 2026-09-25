import { http, type ApiResponse } from './http'

export type LedgerCategory = 'OLD' | 'BAD' | 'FUTURE'
export type LedgerDirection = 'RECEIVE' | 'PAY'
export type LedgerStatus = 'TO_VERIFY' | 'CONFIRMED' | 'DISPUTED' | 'VOID'
export type LedgerRole = 'OWNER' | 'EDITOR' | 'VIEWER'

export interface LedgerBook {
  id: string
  title: string
  ownerMemberId: string
  role: LedgerRole
  updatedAt: string
}
export interface LedgerMemberAccess { memberId: string; role: 'EDITOR' | 'VIEWER' }
export interface LedgerSettlement {
  id: string
  amount: number
  settledOn: string
  note: string | null
  createdBy: string
  createdAt: string
}
export interface LedgerEntrySnapshot {
  title: string
  counterparty: string
  category: LedgerCategory
  direction: LedgerDirection
  status: LedgerStatus
  amount: number
  occurredOn: string | null
  dueOn: string | null
  evidence: string | null
  disputeNote: string | null
  nextAction: string | null
}
export interface LedgerEntryRevision {
  id: string
  before: LedgerEntrySnapshot
  after: LedgerEntrySnapshot
  changedBy: string
  changedAt: string
}
export interface LedgerEntry {
  id: string
  bookId: string
  title: string
  counterparty: string
  category: LedgerCategory
  direction: LedgerDirection
  status: LedgerStatus
  amount: number
  settledAmount: number
  remainingAmount: number
  occurredOn: string | null
  dueOn: string | null
  evidence: string | null
  disputeNote: string | null
  nextAction: string | null
  createdBy: string
  updatedBy: string
  version: number
  updatedAt: string
  settlements: LedgerSettlement[]
  revisions: LedgerEntryRevision[]
}
export interface LedgerSummary {
  receivable: number
  payable: number
  net: number
  disputedCount: number
  toVerifyCount: number
  undatedConfirmedCount: number
}
export interface LedgerBookDetail {
  book: LedgerBook
  members: LedgerMemberAccess[]
  entries: LedgerEntry[]
  summary: LedgerSummary
}
export interface LedgerEntryInput {
  title: string
  counterparty: string
  category: LedgerCategory
  direction: LedgerDirection
  status: LedgerStatus
  amount: number
  occurredOn: string | null
  dueOn: string | null
  evidence: string
  disputeNote: string
  nextAction: string
  version: number | null
}

const root = '/api/ledger/books'
const path = (bookId: string) => `${root}/${encodeURIComponent(bookId)}`
const entryPath = (bookId: string, entryId: string) => `${path(bookId)}/entries/${encodeURIComponent(entryId)}`

export async function listLedgerBooks(): Promise<LedgerBook[]> {
  return (await http.get<ApiResponse<LedgerBook[]>>(root)).data.data
}
export async function createLedgerBook(title: string): Promise<LedgerBook> {
  return (await http.post<ApiResponse<LedgerBook>>(root, { title })).data.data
}
export async function getLedgerBook(bookId: string): Promise<LedgerBookDetail> {
  return (await http.get<ApiResponse<LedgerBookDetail>>(path(bookId))).data.data
}
export async function shareLedgerBook(bookId: string, memberId: string, role: 'EDITOR' | 'VIEWER'): Promise<void> {
  await http.put(`${path(bookId)}/members/${encodeURIComponent(memberId)}`, { role })
}
export async function revokeLedgerBookAccess(bookId: string, memberId: string): Promise<void> {
  await http.delete(`${path(bookId)}/members/${encodeURIComponent(memberId)}`)
}
export async function createLedgerEntry(bookId: string, input: LedgerEntryInput): Promise<LedgerEntry> {
  return (await http.post<ApiResponse<LedgerEntry>>(`${path(bookId)}/entries`, input)).data.data
}
export async function updateLedgerEntry(bookId: string, entryId: string, input: LedgerEntryInput): Promise<LedgerEntry> {
  return (await http.patch<ApiResponse<LedgerEntry>>(entryPath(bookId, entryId), input)).data.data
}
export async function addLedgerSettlement(bookId: string, entryId: string,
  input: { amount: number; settledOn: string; note: string }): Promise<LedgerEntry> {
  return (await http.post<ApiResponse<LedgerEntry>>(`${entryPath(bookId, entryId)}/settlements`, input)).data.data
}
