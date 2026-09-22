import { http, type ApiResponse } from './http'

export interface DeepSeekPolicy {
  enabled: boolean
  dailyTokenLimit: number
  polishEnabled: boolean
  assistantEnabled: boolean
  tianrenEnabled: boolean
  backgroundEnabled: boolean
}
export interface DeepSeekPolicySnapshot {
  policy: DeepSeekPolicy
  day: string
  usage: { purpose: string; budgetTokens: number; actualTokens: number; requestCount: number }[]
}
export async function fetchDeepSeekPolicy() {
  return (await http.get<ApiResponse<DeepSeekPolicySnapshot>>('/api/settings/deepseek-policy')).data.data
}
export async function saveDeepSeekPolicy(policy: DeepSeekPolicy) {
  return (await http.put<ApiResponse<DeepSeekPolicySnapshot>>('/api/settings/deepseek-policy', policy)).data.data
}
