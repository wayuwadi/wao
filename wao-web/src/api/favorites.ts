import { http, type ApiResponse } from './http'

/** 一条收藏。parentId：任务→项目 id，文档→知识库 id，模块和项目为 null。 */
export interface Favorite {
  type: 'module' | 'project' | 'task' | 'doc'
  targetId: string
  label: string
  parentId: string | null
}

export async function fetchFavorites(): Promise<Favorite[]> {
  const res = await http.get<ApiResponse<Favorite[]>>('/api/favorites')
  return res.data.data
}

export async function apiAddFavorite(type: string, targetId: string): Promise<void> {
  await http.put<ApiResponse<void>>(`/api/favorites/${type}/${targetId}`)
}

export async function apiRemoveFavorite(type: string, targetId: string): Promise<void> {
  await http.delete<ApiResponse<void>>(`/api/favorites/${type}/${targetId}`)
}

/** 成员级偏好键值（如左侧菜单顺序），服务端按登录成员隔离。 */
export async function fetchPreference(key: string): Promise<string> {
  const res = await http.get<ApiResponse<{ value: string }>>(`/api/preferences/${key}`)
  return res.data.data.value
}

export async function savePreference(key: string, value: string): Promise<void> {
  await http.put<ApiResponse<void>>(`/api/preferences/${key}`, { value })
}
