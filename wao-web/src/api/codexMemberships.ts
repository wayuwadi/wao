import { http, type ApiResponse } from './http'
import type { CodexQuotaAccount } from './ai'

export interface CodexMembership { accountKey: string; purchasedAt: string | null; proExpiresAt: string | null; revision: string }
export interface CodexAccountMembership { account: CodexQuotaAccount; membership: CodexMembership }
export async function fetchCodexMemberships() {
  return (await http.get<ApiResponse<CodexAccountMembership[]>>('/api/codex-memberships')).data.data
}
export async function saveCodexMembership(value: CodexMembership) {
  return (await http.put<ApiResponse<CodexMembership>>('/api/codex-memberships', value)).data.data
}
