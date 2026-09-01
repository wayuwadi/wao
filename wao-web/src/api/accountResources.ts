import { http, type ApiResponse } from './http'

export interface AccountResource {
  id: string
  platform: string
  accountLabel: string
  loginIdentifier: string
  phoneNumber: string
  notes: string
  enabled: boolean
  hasSecret: boolean
  revision: number
  createdBy: string
  updatedBy: string
  createdAt: string
  updatedAt: string
}

export interface SaveAccountResource {
  platform: string
  accountLabel: string
  loginIdentifier: string
  phoneNumber: string
  notes: string
  secret?: string
  enabled: boolean
  revision: number | null
}

export interface VerificationMessage {
  id: string
  phoneNumber: string
  sender: string
  source: string
  message: string
  code: string | null
  receivedAt: string
  forwardStatus: 'PENDING' | 'SENT' | 'FAILED'
  forwardError: string
  accountLabels: string[]
}

export interface AccountResourceIntegration {
  ingestTokenConfigured: boolean
  crawlerTokenConfigured: boolean
  notificationWebhookId: string
  ingestPath: string
  latestCodePath: string
}

export interface RotatedIntegrationToken {
  type: 'ingest' | 'crawler'
  token: string
}

const data = async <T>(request: Promise<{ data: ApiResponse<T> }>) => (await request).data.data

export const fetchAccountResources = () =>
  data<AccountResource[]>(http.get('/api/account-resources'))

export const createAccountResource = (body: SaveAccountResource) =>
  data<AccountResource>(http.post('/api/account-resources', body))

export const updateAccountResource = (id: string, body: SaveAccountResource) =>
  data<AccountResource>(http.put(`/api/account-resources/${id}`, body))

export const deleteAccountResource = (id: string, revision: number) =>
  data<null>(http.delete(`/api/account-resources/${id}`, { params: { revision } }))

export const revealAccountResourceSecret = (id: string) =>
  data<{ secret: string | null }>(http.post(`/api/account-resources/${id}/reveal`))

export const fetchVerificationMessages = () =>
  data<VerificationMessage[]>(http.get('/api/account-resources/messages'))

export const retryVerificationForward = (id: string) =>
  data<VerificationMessage>(http.post(`/api/account-resources/messages/${id}/forward`))

export const fetchAccountResourceIntegration = () =>
  data<AccountResourceIntegration>(http.get('/api/account-resources/integration'))

export const saveAccountResourceIntegration = (notificationWebhookId: string) =>
  data<AccountResourceIntegration>(
    http.put('/api/account-resources/integration', { notificationWebhookId }),
  )

export const rotateAccountResourceToken = (type: 'ingest' | 'crawler') =>
  data<RotatedIntegrationToken>(
    http.post(`/api/account-resources/integration/tokens/${type}/rotate`),
  )
