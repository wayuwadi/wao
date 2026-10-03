import { http, type ApiResponse } from './http'
export type RoutinePeriod = 'MORNING' | 'EVENING'
export type RoutineStatus = 'OPEN' | 'DONE' | 'SKIPPED'
export interface RoutineRevision { id: string; effectiveFrom: string; effectiveTo: string | null; title: string; period: RoutinePeriod; weekdays: number[]; sortOrder: number; active: boolean; archived: boolean }
export interface RoutineTemplate { id: string; version: number; currentRevision: RoutineRevision | null; nextRevision: RoutineRevision | null }
export interface RoutineOccurrence { templateId: string; revisionId: string; title: string; period: RoutinePeriod; sortOrder: number; status: RoutineStatus; version: number; recordedAt: string | null; virtual: boolean }
export interface RoutineRange { from: string; to: string; templates: RoutineTemplate[]; days: { date: string; occurrences: RoutineOccurrence[] }[] }
export interface RoutineInput { expectedVersion: number | null; title: string; period: RoutinePeriod; weekdays: number[]; sortOrder: number; active: boolean; archived: boolean }
export interface RoutineWrite { operationId: string; template: RoutineTemplate; effectiveFrom: string }
export interface OccurrenceWrite { operationId: string; occurrence: RoutineOccurrence }
const unwrap = async <T>(call: Promise<{data:ApiResponse<T>}>) => (await call).data.data
export const fetchRoutines = (from: string, to = from) => unwrap<RoutineRange>(http.get('/api/todo/routines', { params: { from, to } }))
export const writeRoutine = (id: string, operation: string, body: RoutineInput) => unwrap<RoutineWrite>(http.put(`/api/todo/routines/${id}/operations/${operation}`, body))
export const writeOccurrence = (id: string, date: string, operation: string, body: { expectedVersion: number; status: RoutineStatus }) => unwrap<OccurrenceWrite>(http.put(`/api/todo/routines/${id}/occurrences/${date}/operations/${operation}`, body))
