import { http, type ApiResponse } from './http'

export type DevelopmentStatus =
  | 'QUEUED'
  | 'ANALYZING'
  | 'WAITING_DECISION'
  | 'IMPLEMENTING'
  | 'VERIFYING'
  | 'PR_READY'
  | 'MERGED'
  | 'DEPLOYING'
  | 'RELEASED'
  | 'FAILED'
  | 'PAUSED'
  | 'NO_CHANGE'
export interface DevelopmentPolicy {
  enabled: boolean
  version: number
  intervalHours: number
  maxParallel: number
}
export interface DevelopmentPlan {
  recommendedIndex: number
  options: { title: string; description: string }[]
  reason: string
  goal: string
}
export interface DevelopmentAgent {
  identity?: { subjectId: string; versionId: string; assetId: string; name?: string } | null
  id: string
  title: string
  goal: string
  status: string
  dependsOn: string[]
  paths: string[]
}
export interface DevelopmentEvidence {
  validation: string[]
  prUrl: string | null
  mergeSha: string | null
  publicVersion: string | null
  releaseVerified: boolean
  artifacts: { label: string; url: string }[]
}
export interface DevelopmentDecision {
  id: string
  revision: number
  question: string
  reason: string
  status: string
  deliveryStatus: string
  reply: string | null
}
export interface DevelopmentRun {
  id: string
  featureId: string
  title: string
  goal: string
  status: DevelopmentStatus
  phase: string
  plan: DevelopmentPlan | null
  agents: DevelopmentAgent[]
  evidence: DevelopmentEvidence | null
  error: string | null
  decision: DevelopmentDecision | null
  createdAt: string
  updatedAt: string
  version: number
}
export interface DevelopmentOverview {
  canManage: boolean
  policy: DevelopmentPolicy
  worker: { online: boolean; lastSeenAt: string | null; message: string }
  features: { id: string; name: string }[]
  runs: DevelopmentRun[]
}
export async function fetchDevelopmentOverview(): Promise<DevelopmentOverview> {
  return (await http.get<ApiResponse<DevelopmentOverview>>('/api/development/overview')).data.data
}
export async function saveDevelopmentPolicy(policy: DevelopmentPolicy): Promise<DevelopmentPolicy> {
  return (await http.put<ApiResponse<DevelopmentPolicy>>('/api/development/policy', policy)).data
    .data
}
export async function createDevelopmentRun(featureId: string): Promise<DevelopmentRun> {
  return (await http.post<ApiResponse<DevelopmentRun>>('/api/development/runs', { featureId })).data
    .data
}
export async function fetchDevelopmentRun(id: string): Promise<DevelopmentRun> {
  return (
    await http.get<ApiResponse<DevelopmentRun>>(`/api/development/runs/${encodeURIComponent(id)}`)
  ).data.data
}
export async function retryDevelopmentRun(id: string, version: number): Promise<DevelopmentRun> {
  return (
    await http.post<ApiResponse<DevelopmentRun>>(
      `/api/development/runs/${encodeURIComponent(id)}/retry`,
      { version },
    )
  ).data.data
}
export async function replyDevelopmentDecision(
  id: string,
  revision: number,
  action: 'approve' | 'reject',
  reply: string,
): Promise<DevelopmentRun> {
  return (
    await http.post<ApiResponse<DevelopmentRun>>(
      `/api/development/decisions/${encodeURIComponent(id)}`,
      { revision, action, reply },
    )
  ).data.data
}
