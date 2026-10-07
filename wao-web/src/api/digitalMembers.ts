import { http, type ApiResponse } from './http'

export type DigitalGender = 'male' | 'female' | 'unknown'
export interface DigitalAsset {
  id: string
  name: string
  url: string
  width: number
  height: number
  role: 'reference' | 'portrait'
  createdAt: string
}
export interface DigitalVersion {
  id: string
  number: number
  state: 'candidate' | 'confirmed'
  assetIds: string[]
  portraitAssetId: string | null
  createdAt: string
}
export interface DigitalLook {
  id: string
  name: string
  style: string
  revision: number
  versions: DigitalVersion[]
}
export interface DigitalSubject {
  id: string
  name: string
  kind: 'human' | 'pet'
  relation: string
  gender: DigitalGender
  traits: string
  revision: number
  state: 'active' | 'archived'
  mainAssetId: string | null
  assets: DigitalAsset[]
  looks: DigitalLook[]
  history: Array<{
    purpose?: string
    runId: string
    versionId: string
    mode: string
    status: string
    createdAt: string
  }>
}
export interface DigitalBinding {
  subjectId: string | null
  versionId: string | null
  revision: number
}
export interface DigitalLibrary {
  subjects: DigitalSubject[]
  binding: DigitalBinding
  profile: { gender: DigitalGender; revision: number }
  capability: { referenceImage: boolean; message: string }
}
export interface IdentitySelection {
  mode: 'default' | 'explicit' | 'none'
  subjectId?: string
  versionId?: string
}
export type DigitalSubjectInput = Pick<
  DigitalSubject,
  'name' | 'kind' | 'relation' | 'gender' | 'traits'
>
export type DigitalSubjectUpdate = Omit<DigitalSubjectInput, 'kind'> & {
  expectedRevision: number
  state?: DigitalSubject['state']
  mainAssetId?: string | null
}
async function data<T>(request: Promise<{ data: ApiResponse<T> }>): Promise<T> {
  return (await request).data.data
}
const key = encodeURIComponent
export const fetchDigitalLibrary = () =>
  data(http.get<ApiResponse<DigitalLibrary>>('/api/digital-members'))
export const createDigitalSubject = (input: DigitalSubjectInput) =>
  data(http.post<ApiResponse<DigitalSubject>>('/api/digital-members', input))
export const updateDigitalSubject = (id: string, input: DigitalSubjectUpdate) =>
  data(http.patch<ApiResponse<DigitalSubject>>(`/api/digital-members/${key(id)}`, input))
export async function uploadDigitalAsset(
  id: string,
  file: File,
  role: DigitalAsset['role'],
): Promise<DigitalAsset> {
  const form = new FormData()
  form.append('file', file)
  form.append('role', role)
  return data(
    http.post<ApiResponse<DigitalAsset>>(`/api/digital-members/${key(id)}/assets`, form, {
      timeout: 60_000,
    }),
  )
}
export const deleteDigitalAsset = (id: string) =>
  data(http.delete<ApiResponse<unknown>>(`/api/digital-assets/${key(id)}`))
export async function fetchDigitalAssetBlob(id: string): Promise<Blob> {
  const response = await http.get<Blob>(`/api/digital-assets/${key(id)}/raw?preview=true`, {
    responseType: 'blob',
    timeout: 60_000,
  })
  if (!response.data.type.startsWith('image/')) throw new Error('照片读取失败，请重试')
  return response.data
}
export interface DigitalLookInput {
  name: string
  style: string
  assetIds: string[]
  portraitAssetId?: string | null
}
export const createDigitalLook = (id: string, input: DigitalLookInput) =>
  data(http.post<ApiResponse<DigitalLook>>(`/api/digital-members/${key(id)}/looks`, input))
export const createDigitalVersion = (
  id: string,
  input: Omit<DigitalLookInput, 'name' | 'style'> & { expectedRevision: number },
) => data(http.post<ApiResponse<DigitalLook>>(`/api/digital-looks/${key(id)}/versions`, input))
export const confirmDigitalVersion = (id: string) =>
  data(http.post<ApiResponse<DigitalSubject>>(`/api/digital-versions/${key(id)}/confirm`))
export const saveDigitalBinding = (input: {
  subjectId: string | null
  versionId: string | null
  expectedRevision: number
}) => data(http.put<ApiResponse<DigitalBinding>>('/api/me/digital-member', input))
export const saveDigitalProfile = (gender: DigitalGender, expectedRevision: number) =>
  data(
    http.put<ApiResponse<DigitalLibrary['profile']>>('/api/me/profile', {
      gender,
      expectedRevision,
    }),
  )
export const generateDigitalLook = (
  id: string,
  input: Omit<DigitalLookInput, 'portraitAssetId'> & { requestId: string },
) =>
  data(http.post<ApiResponse<{ runId: string }>>(`/api/digital-members/${key(id)}/generate`, input))
export const fetchDigitalGeneration = (id: string, runId: string) =>
  data(
    http.get<
      ApiResponse<{
        status: string
        message: string
        lookId: string | null
        versionId: string | null
      }>
    >(`/api/digital-members/${key(id)}/generations/${key(runId)}`),
  )
