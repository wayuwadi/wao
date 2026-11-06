import { http, type ApiResponse } from './http'
export type TaskOrganizationKey = 'todo-named-filters-v1' | 'todo-list-folders-v1'
export async function saveTaskOrganizationPreference(key: TaskOrganizationKey, value: string, expected: string) {
  await http.put<ApiResponse<void>>(`/api/preferences/${key}`, { value, expected })
}
