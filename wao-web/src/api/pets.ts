import { http, type ApiResponse } from './http'
export interface PetCard {
  id: string
  slug: string
  displayName: string
  description: string
  spriteVersion: number
  source: string
  license: string
  sha256: string
  packageSize: number
  listed: boolean
  handle: string
  author: string
  createdAt: string
}
export interface PetPage {
  items: PetCard[]
  page: number
  hasMore: boolean
}
export async function fetchPets(keyword = '', page = 1) {
  return (await http.get<ApiResponse<PetPage>>('/api/public/pets', { params: { keyword, page } }))
    .data.data
}
export async function fetchPet(id: string) {
  return (await http.get<ApiResponse<PetCard>>(`/api/public/pets/${encodeURIComponent(id)}`)).data
    .data
}
export async function fetchMyPets() {
  return (await http.get<ApiResponse<PetCard[]>>('/api/pets/mine')).data.data
}
export async function createPet(body: FormData) {
  return (await http.post<ApiResponse<PetCard>>('/api/pets', body, { timeout: 60_000 })).data.data
}
export async function setPetVisibility(id: string, listed: boolean, rightsConfirmed = false) {
  return (
    await http.put<ApiResponse<PetCard>>(`/api/pets/${encodeURIComponent(id)}/visibility`, {
      listed,
      rightsConfirmed,
    })
  ).data.data
}
export function petFileUrl(id: string, file: 'sprite' | 'download', own = false) {
  return `/api/${own ? '' : 'public/'}pets/${encodeURIComponent(id)}/${file}`
}
