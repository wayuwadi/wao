import { http, type ApiResponse } from './http'

export type GarmentStatus = 'WARDROBE' | 'CLEANING' | 'IDLE' | 'DONATED'
export type AppearancePlanType = 'EVENT' | 'CLEANING' | 'STORAGE' | 'PURCHASE'
export type AppearancePlanStatus = 'OPEN' | 'DONE'

export interface AppearanceGarment {
  id: string
  name: string
  category: string
  seasons: string[]
  styles: string[]
  colors: string[]
  occasions: string[]
  material: string
  status: GarmentStatus
  imageId: string | null
  purchaseDate: string | null
  lastWornDate: string | null
  wearCount: number
  note: string
}

export interface AppearanceOutfit {
  id: string
  name: string
  garmentIds: string[]
  scenarios: string[]
  imageId: string | null
  note: string
}

export interface AppearanceProfile {
  heightCm: number | null
  weightKg: number | null
  shoulderCm: number | null
  waistCm: number | null
  measuredOn: string | null
  skinTone: string
  faceShape: string
  hairTexture: string
  stylePreferences: string
  avoidances: string
  postureGoal: string
  hairScore: number | null
  clothingScore: number | null
  shoesScore: number | null
  accessoryScore: number | null
  bodyScore: number | null
  presenceScore: number | null
}

export interface AppearancePlan {
  id: string
  type: AppearancePlanType
  title: string
  dueDate: string
  scenario: string
  outfitId: string | null
  status: AppearancePlanStatus
  note: string
}

export interface AppearanceWearLog {
  id: string
  date: string
  outfitId: string | null
  garmentIds: string[]
  feeling: string
}

export interface AppearanceNote {
  id: string
  title: string
  content: string
  tags: string[]
  createdAt: string
}

export interface AppearanceWorkspace {
  garments: AppearanceGarment[]
  outfits: AppearanceOutfit[]
  profile: AppearanceProfile
  plans: AppearancePlan[]
  wearLogs: AppearanceWearLog[]
  notes: AppearanceNote[]
  principles: string
  updatedAt: string | null
}

export interface AppearancePhoto {
  id: string
  fileName: string
  contentType: string
  byteSize: number
  url: string
}

const data = async <T>(request: Promise<{ data: ApiResponse<T> }>) => (await request).data.data

export const fetchAppearanceWorkspace = () => data<AppearanceWorkspace>(http.get('/api/appearance'))
export const saveAppearanceWorkspace = (body: AppearanceWorkspace) =>
  data<AppearanceWorkspace>(http.put('/api/appearance', body))
export async function uploadAppearancePhoto(file: File) {
  const form = new FormData()
  form.append('file', file)
  return data<AppearancePhoto>(http.post('/api/appearance/photos', form, { timeout: 60_000 }))
}
export const deleteAppearancePhoto = (id: string) =>
  data<null>(http.delete(`/api/appearance/photos/${id}`))

export const appearancePhotoUrl = (id: string | null) => (id ? `/api/appearance/photos/${id}` : '')
