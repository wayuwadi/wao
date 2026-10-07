import type { ReleaseAction } from '@/types/featureExplorer'
import { canExperienceRelease, canRequestReleaseExperience } from '@/utils/releaseActions'

export const WEB_UPDATE_EVENT = 'plugin_web_update_notice'
export const WORKSPACE_RELOAD_STATE_EVENT = 'wao-workspace-reload-state'
export const OPEN_RELEASE_HIGHLIGHTS_EVENT = 'wao-open-release-highlights'
const PENDING_RELEASE_VERSION_KEY = 'wao-pending-release-highlights-version'
const PENDING_EXPERIENCE_KEY = 'wao-pending-release-experience'
const EXPERIENCE_MAX_AGE = 10 * 60_000

export interface ReleaseExperience {
  featureId: string
  action?: ReleaseAction
}

export interface WebUpdateEventDetail {
  version?: string
}

export interface WorkspaceReloadDetail {
  targetVersion?: string
  experience?: ReleaseExperience
}

export interface WorkspaceReloadState {
  state: 'saving' | 'reloading' | 'failed'
  message?: string
}

export function reportWorkspaceReloadState(detail: WorkspaceReloadState) {
  window.dispatchEvent(new CustomEvent(WORKSPACE_RELOAD_STATE_EVENT, { detail }))
}

export function reloadToLatestVersion(targetVersion?: string, experience?: ReleaseExperience) {
  // App owns the save barrier for tasks, forms and beforeunload guards.
  window.dispatchEvent(
    new CustomEvent<WorkspaceReloadDetail>('wao-workspace-reload', {
      detail: { targetVersion: targetVersion?.trim() || undefined, experience },
    }),
  )
}

/** 保存屏障通过后才记录下一页入口，普通刷新会清除旧请求。 */
export function rememberReleaseReload(version: string, member: string, experience?: ReleaseExperience) {
  if (!version && !experience) { clearReleaseReload(); return }
  sessionStorage.removeItem(PENDING_EXPERIENCE_KEY)
  if (experience) {
    if (!member || !version || !canRequestReleaseExperience(experience.featureId, experience.action))
      throw new Error('功能入口暂不可用，请重新读取更新说明')
    sessionStorage.setItem(PENDING_EXPERIENCE_KEY, JSON.stringify({
      version, member, createdAt: Date.now(), featureId: experience.featureId,
      action: experience.action && { target: experience.action.target, label: experience.action.label },
    }))
  }
  try {
    rememberPendingReleaseVersion(version)
  } catch (error) {
    sessionStorage.removeItem(PENDING_EXPERIENCE_KEY)
    throw error
  }
}

export function clearReleaseReload() {
  try {
    sessionStorage.removeItem(PENDING_EXPERIENCE_KEY)
    sessionStorage.removeItem(PENDING_RELEASE_VERSION_KEY)
  } catch { /* 存储被禁用时仍保留页面。 */ }
}

/** 同标签页、同成员、目标制品到位后仅消费一次；不保存 URL 或业务操作。 */
export function consumeReleaseExperience(version: string, member: string): ReleaseExperience | 'version-mismatch' | null {
  try {
    const raw = sessionStorage.getItem(PENDING_EXPERIENCE_KEY)
    sessionStorage.removeItem(PENDING_EXPERIENCE_KEY)
    if (!raw) return null
    const value = JSON.parse(raw)
    if (!value || value.member !== member || typeof value.createdAt !== 'number' ||
      Date.now() - value.createdAt > EXPERIENCE_MAX_AGE || value.createdAt > Date.now() ||
      !canExperienceRelease(value.featureId, value.action)) return null
    if (value.version !== version) return 'version-mismatch'
    return { featureId: value.featureId, action: value.action }
  } catch { return null }
}

/** 只在全部草稿安全落库后记录；刷新失败不会误触发更新介绍。 */
export function rememberPendingReleaseVersion(version: string) {
  if (!version.trim()) return
  sessionStorage.setItem(PENDING_RELEASE_VERSION_KEY, version.trim())
}

/** 只消费一次安全刷新标记，用于匹配当前制品的轻提示，不自动弹出介绍。 */
export function consumePendingReleaseVersion() {
  try {
    const version = sessionStorage.getItem(PENDING_RELEASE_VERSION_KEY) || ''
    sessionStorage.removeItem(PENDING_RELEASE_VERSION_KEY)
    return version
  } catch { return '' }
}

export function openReleaseHighlights() {
  window.dispatchEvent(new Event(OPEN_RELEASE_HIGHLIGHTS_EVENT))
}
