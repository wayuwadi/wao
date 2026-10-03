import { http, type ApiResponse } from './http'
import type { Doc, RichDocContent, Task, TaskStep } from '@/types'

export type SchedulePeriod = 'MORNING' | 'AFTERNOON' | 'EVENING'
export interface DailySchedule { start: string | null; startAt: string | null; endAt: string | null; schedulePeriod: SchedulePeriod | null }
export interface DayEvent { id: number; type: string; occurredAt: string; businessDate: string; late: boolean; summary: string }
export interface DayTask {
  task: Task
  snapshot: { title: string; status: Task['status']; steps: TaskStep[]; subs: TaskStep[]; owner: string; parts: string[] } & DailySchedule
  source: 'CURRENT' | 'RECORDED'; events: DayEvent[]; planned: boolean; finished: boolean
}
export interface DayNote { docId: string; title: string; createdAt: string; updatedAt: string; businessDate: string; late: boolean; taskId: string | null }
export interface DayWorkspace {
  date: string; recordingStartedAt: string | null; historyCoverage: 'RECORDED' | 'BEFORE_BASELINE'
  tasks: DayTask[]; notes: DayNote[]
  summary: { plannedCount: number; finishedCount: number; stepDoneCount: number; noteCount: number }
}
export interface ScheduleWrite { operationId: string; task: Task; previousSchedule: DailySchedule; appliedAt: string }
export interface NoteSubmission { docId: string; title: string; content: RichDocContent; tags: string[]; autoTitle: boolean; autoImage: boolean; autoPolish: boolean }
export interface DayNoteWrite { operationId: string; doc: Doc; task: Task | null; businessDate: string; late: boolean }
export interface TodoReceipt<T> { status: 'COMMITTED' | 'UNCONFIRMED'; operationId: string; type?: string; result?: T }
const unwrap = async <T>(request: Promise<{ data: ApiResponse<T> }>) => (await request).data.data
export const fetchDayWorkspace = (date: string) => unwrap<DayWorkspace>(http.get(`/api/todo/days/${date}`))
export const fetchDaySummaries = (from: string, to: string) => unwrap<{ from: string; to: string; days: ({ date: string; hasHistory: boolean } & DayWorkspace['summary'])[] }>(http.get('/api/todo/days', { params: { from, to } }))
export const fetchTodoReceipt = <T>(id: string) => unwrap<TodoReceipt<T>>(http.get(`/api/todo/operations/${id}`))
export const writeSchedule = (id: string, operation: string, schedule: DailySchedule & { expectedEditVersion: string }) =>
  unwrap<ScheduleWrite>(http.put(`/api/tasks/${encodeURIComponent(id)}/schedule-operations/${operation}`, schedule))
export const writeDayNote = (operation: string, request: NoteSubmission & { businessDate: string; taskId?: string; expectedTaskEditVersion?: string; expectedDocRev?: number }) =>
  unwrap<DayNoteWrite>(http.put(`/api/todo/note-operations/${operation}`, request))

export class PreviousTodoWriteRecovered<T> extends Error {
  constructor(public result: T) { super('上一次保存已确认，最新输入仍保留，请再次保存最新内容') }
}
interface Pending { operation: string; request: unknown }
const status = (cause: unknown) => (cause as { status?: number; response?: { status?: number } })?.status ?? (cause as { response?: { status?: number } })?.response?.status
/** Retain the exact request before sending; a lost response must never create a second mutation. */
export async function saveTodoWithReceipt<T>(scope: string, account: string, request: unknown,
  send: (operation: string, request: unknown) => Promise<T>, currentAccount: () => string, storage: Storage = localStorage): Promise<T> {
  const key = `wao-todo-operation-v1:${encodeURIComponent(account)}:${encodeURIComponent(scope)}`
  const assertAccount = () => { if (!account || currentAccount() !== account) throw new Error('登录账号已变化，原账号输入仍保留') }
  assertAccount()
  let pending: Pending | null
  try { pending = JSON.parse(storage.getItem(key) || 'null') as Pending | null } catch { throw new Error('无法读取保存回执，输入仍保留，请检查浏览器存储') }
  const clear = () => storage.removeItem(key)
  const submit = async (item: Pending) => {
    try { const result = await send(item.operation, item.request); assertAccount(); clear(); return result }
    catch (cause) {
      assertAccount()
      if ([400, 404, 409, 413, 422].includes(status(cause) ?? 0)) { clear(); throw cause }
      try { const receipt = await fetchTodoReceipt<T>(item.operation); assertAccount(); if (receipt.status === 'COMMITTED' && receipt.result) { clear(); return receipt.result } }
      catch { /* Preserve the exact request while the outcome remains unknown. */ }
      throw new Error('保存结果待确认，输入已保留；重试会先查询原操作')
    }
  }
  if (pending) {
    const receipt = await fetchTodoReceipt<T>(pending.operation)
    assertAccount()
    const previous = receipt.status === 'COMMITTED' && receipt.result ? (clear(), receipt.result) : await submit(pending)
    if (JSON.stringify(pending.request) !== JSON.stringify(request)) throw new PreviousTodoWriteRecovered(previous)
    return previous
  }
  pending = { operation: crypto.randomUUID(), request: JSON.parse(JSON.stringify(request)) as unknown }
  storage.setItem(key, JSON.stringify(pending))
  return submit(pending)
}
