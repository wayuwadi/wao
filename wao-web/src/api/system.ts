import { http, type ApiResponse } from './http'
import type {
  BuiltinMenuConfig,
  BuiltinMenuRegistry,
  ExternalAppConfig,
  ExternalAppRegistry,
} from '@/config/nav'

/**
 * 系统级配置接口：网站信息、一级导航、飞书通知与飞书群聊同步。
 *
 * 契约见 docs/api/board.yaml。密钥类字段只回脱敏形态（`urlMask` / `appSecretMask`），
 * 明文永远不出服务端；表单里留空即表示「不修改」。
 */

async function get<T>(url: string, params?: Record<string, string>): Promise<T> {
  const res = await http.get<ApiResponse<T>>(url, { params })
  return res.data.data
}
async function put<T>(url: string, body?: unknown): Promise<T> {
  const res = await http.put<ApiResponse<T>>(url, body)
  return res.data.data
}
async function post<T>(url: string, params?: Record<string, string>): Promise<T> {
  const res = await http.post<ApiResponse<T>>(url, undefined, { params })
  return res.data.data
}
async function postWithBody<T>(url: string, body?: unknown): Promise<T> {
  const res = await http.post<ApiResponse<T>>(url, body)
  return res.data.data
}

/** 飞书通知 webhook 配置。 */
export interface FeishuWebhookConfig {
  urlMask: string
  urlSet: boolean
  enabled: boolean
}

export const fetchWebhookConfig = () => get<FeishuWebhookConfig>('/api/settings/feishu-webhook')
/** url 传 undefined 表示不改地址，只切换启用开关。 */
export const saveWebhookConfig = (payload: { url?: string; enabled?: boolean }) =>
  put<FeishuWebhookConfig>('/api/settings/feishu-webhook', payload)
export const testWebhook = () => post<void>('/api/settings/feishu-webhook/test')

/** 可供 WAO 各功能复用的一项飞书自定义机器人；接口永不返回 Webhook 明文。 */
export interface FeishuWebhookEndpoint {
  id: string
  name: string
  description: string
  urlMask: string
  urlSet: boolean
  enabled: boolean
  systemDefault: boolean
  createdAt: string
  updatedAt: string
}

export interface FeishuWebhookEndpointInput {
  name: string
  description: string
  /** 新增时必填；编辑时留空表示保留服务端密文。 */
  url?: string
  enabled: boolean
  systemDefault: boolean
}

export const fetchWebhookEndpoints = () => get<FeishuWebhookEndpoint[]>('/api/settings/webhooks')
export const createWebhookEndpoint = (payload: FeishuWebhookEndpointInput) =>
  postWithBody<FeishuWebhookEndpoint>('/api/settings/webhooks', payload)
export const updateWebhookEndpoint = (id: string, payload: FeishuWebhookEndpointInput) =>
  put<FeishuWebhookEndpoint>(`/api/settings/webhooks/${id}`, payload)
export async function deleteWebhookEndpoint(id: string): Promise<void> {
  await http.delete<ApiResponse<void>>(`/api/settings/webhooks/${id}`)
}
export const testWebhookEndpoint = (id: string) => post<void>(`/api/settings/webhooks/${id}/test`)

/** 网站名称、简介与公开备案信息。 */
export interface SiteInfo {
  siteName: string
  description: string
  icpNumber: string
  icpUrl: string
}

export const fetchPublicSiteInfo = () => get<SiteInfo>('/api/public/site-info')
export const fetchSiteInfo = () => get<SiteInfo>('/api/settings/site-info')
export const saveSiteInfo = (payload: SiteInfo) => put<SiteInfo>('/api/settings/site-info', payload)

/** 自动任务统一遵守的每日断网时段，使用上海时间且支持跨午夜。 */
export interface NetworkOutageWindow {
  enabled: boolean
  startTime: string
  endTime: string
}

export const fetchNetworkOutageWindow = () =>
  get<NetworkOutageWindow>('/api/settings/network-outage-window')
export const saveNetworkOutageWindow = (payload: NetworkOutageWindow) =>
  put<NetworkOutageWindow>('/api/settings/network-outage-window', payload)

/** 微信公众号扫码关注登录配置；两个密钥字段始终只返回脱敏形态。 */
export interface WechatOfficialLoginConfig {
  enabled: boolean
  appId: string
  appSecretMask: string
  appSecretSet: boolean
  tokenMask: string
  tokenSet: boolean
  configured: boolean
  callbackPath: string
}

export const fetchWechatOfficialLoginConfig = () =>
  get<WechatOfficialLoginConfig>('/api/settings/login/wechat-official')
export const saveWechatOfficialLoginConfig = (payload: {
  enabled: boolean
  appId: string
  appSecret?: string
  token?: string
}) => put<WechatOfficialLoginConfig>('/api/settings/login/wechat-official', payload)

/** 文件与图床当前真实生效的上传限制和公开下载校验策略。 */
export interface FileSettings {
  fileMaxMb: number
  imageMaxMb: number
  downloadCaptchaEnabled: boolean
}

export const fetchFileSettings = () => get<FileSettings>('/api/settings/files')
export const saveFileSettings = (payload: FileSettings) =>
  put<FileSettings>('/api/settings/files', payload)

/** WAO 壳内所有外部应用的全局菜单与嵌入入口注册表。 */
export const fetchExternalAppRegistry = () =>
  get<ExternalAppRegistry>('/api/settings/navigation/external-apps')
export const saveExternalAppRegistry = (apps: ExternalAppConfig[]) =>
  put<ExternalAppRegistry>('/api/settings/navigation/external-apps', { apps })
export const fetchBuiltinMenuRegistry = () =>
  get<BuiltinMenuRegistry>('/api/settings/navigation/builtin-menus')
export const saveBuiltinMenuRegistry = (menus: BuiltinMenuConfig[]) =>
  put<BuiltinMenuRegistry>('/api/settings/navigation/builtin-menus', { menus })

/** 群聊发言人映射：飞书 open_id ↔ 显示名。 */
export interface FeishuSender {
  id: string
  name: string
}

/** 飞书群聊同步配置。 */
export interface ChatSyncConfig {
  appId: string
  appSecretMask: string
  appSecretSet: boolean
  chatId: string
  chatName: string
  kbId: string
  senders: FeishuSender[]
}

/** 机器人所在的群。 */
export interface FeishuChat {
  chatId: string
  name: string
}

export const fetchChatSyncConfig = () => get<ChatSyncConfig>('/api/feishu/chat-sync/config')
/** appSecret 传 undefined 表示保留已存的密钥。 */
export const saveChatSyncConfig = (payload: {
  appId?: string
  appSecret?: string
  chatId?: string
  chatName?: string
  kbId?: string
  senders?: FeishuSender[]
}) => put<ChatSyncConfig>('/api/feishu/chat-sync/config', payload)
export const fetchFeishuChats = () => get<FeishuChat[]>('/api/feishu/chat-sync/chats')
export const fetchFeishuChatMembers = (chatId: string) =>
  get<FeishuSender[]>('/api/feishu/chat-sync/members', { chatId })
/** 手动同步一天（默认今天）或一段连续日期，返回结果摘要。 */
export const runChatSync = (range?: { day?: string; startDay?: string; endDay?: string }) =>
  post<string>('/api/feishu/chat-sync/run', range)

/** 群聊快速收集：@机器人 直接建任务，以及「收集中」自动分享时要 @ 的人。 */
export interface QuickCollectConfig {
  enabled: boolean
  projectId: string
  verificationToken: string
  hasVerificationToken: boolean
  encryptKey: string
  hasEncryptKey: boolean
  mentionOpenIds: string[]
  receivedEmoji: string
  completedEmoji: string
}

export const fetchQuickCollectConfig = () =>
  get<QuickCollectConfig>('/api/feishu/quick-collect/config')
/** verificationToken / encryptKey 传 undefined 表示保留已存的那一份。 */
export const saveQuickCollectConfig = (payload: {
  enabled?: boolean
  projectId?: string
  verificationToken?: string
  encryptKey?: string
  mentionOpenIds?: string[]
  receivedEmoji?: string
  completedEmoji?: string
}) => put<QuickCollectConfig>('/api/feishu/quick-collect/config', payload)
