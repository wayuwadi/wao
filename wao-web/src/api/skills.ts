import { http, type ApiResponse } from './http'

export interface SkillCategory { key: string; name: string }
export interface SkillPublisher { handle: string; displayName: string }
export interface SkillCard {
  id: string; slug: string; displayName: string; summary: string; category: string; tags: string[]
  status: string; downloads: number; favorites: number; updatedAt: string; publisher: SkillPublisher
  version: string; compatiblePlatforms: string[]
}
export interface SkillVersion {
  id: string; version: string; state: string; changelog: string; license: string
  testedPlatforms: string[]; compatiblePlatforms: string[]; packageSize: number; unpackedSize: number
  fileCount: number; sha256: string; files: { path: string; size: number }[]; findings: string[]
  skillMd: string; reviewNote: string; submittedAt: string; reviewedAt: string
}
export interface SkillDetail { skill: SkillCard; publishedVersion: SkillVersion; versions: SkillVersion[]; favorite: boolean }
export interface SkillPage { items: SkillCard[]; total: number; page: number; pageSize: number }
export interface MySkill {
  id: string; slug: string; displayName: string; summary: string; category: string; status: string
  downloads: number; favorites: number; versions: SkillVersion[]
}
export interface ReviewItem { skill: MySkill; version: SkillVersion; publisher: SkillPublisher }
export interface DraftMetadata {
  skillId?: string; slug: string; displayName: string; summary: string; category: string; tags: string[]
  version: string; changelog: string; license: string; testedPlatforms: string[]; rightsConfirmed: boolean
}

const data = <T>(response: { data: ApiResponse<T> }) => response.data.data

export async function fetchSkillCategories() { return data(await http.get<ApiResponse<SkillCategory[]>>('/api/public/skill-categories')) }
export async function fetchSkills(params: Record<string, string | number | undefined>) { return data(await http.get<ApiResponse<SkillPage>>('/api/public/skills', { params })) }
export async function fetchSkill(handle: string, slug: string) { return data(await http.get<ApiResponse<SkillDetail>>(`/api/public/skills/${encodeURIComponent(handle)}/${encodeURIComponent(slug)}`)) }
export const skillDownloadUrl = (handle: string, slug: string, version?: string) => `/api/public/skills/${encodeURIComponent(handle)}/${encodeURIComponent(slug)}/download${version ? `?version=${encodeURIComponent(version)}` : ''}`
export async function fetchPublisher() { return data(await http.get<ApiResponse<SkillPublisher | null>>('/api/skills/publisher/me')) }
export async function savePublisher(body: SkillPublisher) { return data(await http.put<ApiResponse<SkillPublisher>>('/api/skills/publisher/me', body)) }
export async function fetchMySkills() { return data(await http.get<ApiResponse<MySkill[]>>('/api/skills/mine')) }
export async function createSkillDraft(metadata: DraftMetadata, archive: File | null, files: File[], onProgress: (value: number) => void) {
  const form = new FormData()
  form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }))
  if (archive) form.append('archive', archive)
  else files.forEach((file) => { form.append('files', file); form.append('paths', file.webkitRelativePath || file.name) })
  return data(await http.post<ApiResponse<MySkill>>('/api/skills', form, {
    timeout: 60_000,
    onUploadProgress: (event) => onProgress(event.total ? Math.round(event.loaded * 100 / event.total) : 0),
  }))
}
export async function patchSkillDraft(skillId: string, versionId: string, body: Omit<DraftMetadata, 'skillId' | 'slug' | 'version'>) { return data(await http.patch<ApiResponse<SkillVersion>>(`/api/skills/${skillId}/versions/${versionId}`, body)) }
export async function submitSkill(skillId: string, versionId: string) { return data(await http.post<ApiResponse<SkillVersion>>(`/api/skills/${skillId}/versions/${versionId}/submit`)) }
export async function deleteSkillDraft(skillId: string, versionId: string) { await http.delete(`/api/skills/${skillId}/versions/${versionId}`) }
export async function setSkillListed(skillId: string, value: boolean) { await http.put(`/api/skills/${skillId}/listed`, null, { params: { value } }) }
export async function setSkillFavorite(skillId: string, value: boolean) { await http.put(`/api/skills/${skillId}/favorite`, null, { params: { value } }) }
export async function fetchSkillReviews() { return data(await http.get<ApiResponse<ReviewItem[]>>('/api/admin/skill-reviews')) }
export async function approveSkill(versionId: string) { return data(await http.post<ApiResponse<SkillVersion>>(`/api/admin/skill-reviews/${versionId}/approve`)) }
export async function rejectSkill(versionId: string, note: string) { return data(await http.post<ApiResponse<SkillVersion>>(`/api/admin/skill-reviews/${versionId}/reject`, { note })) }
