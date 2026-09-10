import { http, type ApiResponse } from './http'
import type { NavigationLayout } from '@/config/navigationLayout'

export interface NavigationLayoutSnapshot {
  revision: string
  layout: NavigationLayout | null
}

export async function fetchNavigationLayout(): Promise<NavigationLayoutSnapshot> {
  return (await http.get<ApiResponse<NavigationLayoutSnapshot>>('/api/navigation/layout')).data.data
}

export async function saveNavigationLayout(
  expectedRevision: string,
  layout: NavigationLayout,
): Promise<NavigationLayoutSnapshot> {
  return (
    await http.put<ApiResponse<NavigationLayoutSnapshot>>('/api/navigation/layout', {
      expectedRevision,
      layout,
    })
  ).data.data
}
