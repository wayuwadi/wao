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
}) => put<QuickCollectConfig>('/api/feishu/quick-collect/config', payload)
