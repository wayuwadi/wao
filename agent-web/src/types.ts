export interface AgentSession {
  accountId: string
  displayName: string
  avatarText: string
  workspaceId: string
  workspaceName: string
  role: string
  demoContent: boolean
}

export interface PlatformAccount {
  id: string
  platform: string
  platformLabel: string
  displayName: string
  avatarUrl?: string
  status: string
  scope?: string
  lastError?: string
  accessTokenExpiresAt?: string
  createdAt: string
}

/** 平台能力由后端声明：没有官方接口的平台 publishSupported 为 false，前端不得展示发布入口。 */
export interface PlatformSummary {
  platform: string
  label: string
  oauthSupported: boolean
  publishSupported: boolean
  mediaKinds: string[]
  note: string
  accounts: PlatformAccount[]
}

export interface Hotspot {
  id: string
  source: string
  sourceLabel: string
  title: string
  summary?: string
  url?: string
  heat?: number
  rank: number
  firstSeenAt: string
  lastSeenAt: string
}

/** 某个热点来源最近一次采集的结果；error 非空表示这个源当前挂了。 */
export interface HotspotSourceStatus {
  code: string
  label: string
  available: boolean
  note: string
  fetched: number
  error?: string
  lastAttemptAt?: string
}

export interface TopicSuggestion {
  id: string
  platform: string
  platformLabel: string
  topic: string
  angle?: string
  reason?: string
  fitScore: number
  status: string
  hotspotId?: string
  hotspotTitle?: string
  hotspotUrl?: string
  createdAt: string
}

export interface WorkspaceProfile {
  niche?: string
  audience?: string
  tone?: string
  avoid?: string
  configured: boolean
}

export interface Studio {
  profile: WorkspaceProfile
  sources: HotspotSourceStatus[]
  hotspots: Hotspot[]
  suggestions: TopicSuggestion[]
}

/** 创作用的结构化分段：短视频是口播脚本，公众号是小标题段落。 */
export interface ContentBlock {
  label?: string
  text: string
  seconds?: number
}

export interface PublishTask {
  id: string
  platform: string
  platformLabel: string
  platformAccountId?: string
  platformAccountName?: string
  mediaAssetId?: string
  mediaName?: string
  topic: string
  title?: string
  content?: string
  tags: string[]
  coverText?: string
  blocks: ContentBlock[]
  sourceHotspotId?: string
  sourceHotspotTitle?: string
  status: string
  progress: number
  platformItemId?: string
  errorMessage?: string
  version: number
  createdAt: string
  updatedAt: string
  publishedAt?: string
}

export interface MediaAsset {
  id: string
  kind: string
  originalName: string
  contentType: string
  byteSize: number
}

export interface AiStatus {
  available: boolean
  provider?: string
  model?: string
}

export interface PublishCenter {
  session: AgentSession
  ai: AiStatus
  platforms: PlatformSummary[]
  tasks: PublishTask[]
}
