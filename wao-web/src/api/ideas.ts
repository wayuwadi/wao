import { http, type ApiResponse } from './http'
import type {
  Idea,
  IdeaArtifact,
  IdeaArtifactKind,
  IdeaDetail,
  IdeaIteration,
  IdeaIterationStatus,
  IdeaPriority,
  IdeaResearchRun,
  IdeaStage,
  IdeaTaskLink,
  Project,
} from '@/types'

async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}

export const fetchIdeas = () => data<Idea[]>(http.get('/api/ideas'))
export const fetchIdea = (id: string) => data<Idea>(http.get(`/api/ideas/${id}`))
export const createIdea = (body: { title: string; rawIdea?: string; priority?: IdeaPriority }) =>
  data<Idea>(http.post('/api/ideas', body))
export const patchIdea = (
  id: string,
  body: Partial<
    Pick<
      Idea,
      'title' | 'rawIdea' | 'problem' | 'targetOutcome' | 'priority' | 'ownerId' | 'projectId'
    >
  > & { detail?: IdeaDetail },
) => data<Idea>(http.patch(`/api/ideas/${id}`, body))
export const transitionIdea = (
  id: string,
  body: {
    stage: IdeaStage
    validationConclusion?: string
    validationEvidence?: string
    abandonmentReason?: string
  },
) => data<Idea>(http.post(`/api/ideas/${id}/transition`, body))
export const acceptIdeaResearch = (id: string) =>
  data<Idea>(http.post(`/api/ideas/${id}/research-acceptance`))
export const startIdeaResearch = (id: string) =>
  data<IdeaResearchRun>(http.post(`/api/ideas/${id}/research-runs`))
export const addIdeaArtifact = (
  id: string,
  body: { kind: IdeaArtifactKind; docId: string; title?: string },
) => data<IdeaArtifact>(http.post(`/api/ideas/${id}/artifacts`, body))
export const addIdeaTask = (
  id: string,
  body: {
    taskId?: string
    title?: string
    description?: string
    priority?: IdeaPriority
    ownerId?: string
    requiredMvp?: boolean
  },
) => data<IdeaTaskLink>(http.post(`/api/ideas/${id}/tasks`, body))
export const unlinkIdeaTask = (id: string, taskId: string) =>
  http.delete(`/api/ideas/${id}/tasks/${taskId}`)
export const createIdeaIteration = (
  id: string,
  body: { taskId: string; objective: string; acceptanceCriteria?: string; aiRunId?: string },
) => data<IdeaIteration>(http.post(`/api/ideas/${id}/iterations`, body))
export const patchIdeaIteration = (
  ideaId: string,
  iterationId: string,
  body: {
    status?: IdeaIterationStatus
    feedback?: string
    resultSummary?: string
    reportUrl?: string
  },
) => data<IdeaIteration>(http.patch(`/api/ideas/${ideaId}/iterations/${iterationId}`, body))

/** 把存量独立点子归并为项目；不传 projectId 时由服务端安全推导或新建。 */
export const convertIdeaToProject = (ideaId: string, projectId?: string) =>
  data<Project>(http.post(`/api/ideas/${ideaId}/convert-to-project`, projectId ? { projectId } : {}))
