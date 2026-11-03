import { http, type ApiResponse } from './http'
async function put<T>(url:string,body:unknown):Promise<T>{return (await http.put<ApiResponse<T>>(url,body)).data.data}
import type { Task } from '@/types'
export type TaskMenuAction='abandon'|'restore'|'duplicate'|'note'|'task'
export interface TaskMenuRequest {action:TaskMenuAction;subId?:string;versions:Record<string,string>}
export const saveTaskMenuOperation=(taskId:string,operation:string,accountId:string,change:TaskMenuRequest)=>
  put<{selectedId:string;tasks:Task[]}>(`/api/tasks/${encodeURIComponent(taskId)}/menu-operations/${operation}`,{accountId,change})
