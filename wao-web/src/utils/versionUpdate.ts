export const WEB_UPDATE_EVENT = 'plugin_web_update_notice'
export const WORKSPACE_RELOAD_STATE_EVENT = 'wao-workspace-reload-state'
export const OPEN_RELEASE_HIGHLIGHTS_EVENT = 'wao-open-release-highlights'
const PENDING_RELEASE_VERSION_KEY = 'wao-pending-release-highlights-version'

export interface WebUpdateEventDetail {
  version?: string
}

export interface WorkspaceReloadDetail {
  targetVersion?: string
}

export interface WorkspaceReloadState {
  state: 'saving' | 'reloading' | 'failed'
  message?: string
}

export function reportWorkspaceReloadState(detail: WorkspaceReloadState) {
  window.dispatchEvent(new CustomEvent(WORKSPACE_RELOAD_STATE_EVENT, { detail }))
}

export function reloadToLatestVersion(targetVersion?: string) {
  // App owns the save barrier for tasks, forms and beforeunload guards.
  window.dispatchEvent(
    new CustomEvent<WorkspaceReloadDetail>('wao-workspace-reload', {
      detail: { targetVersion: targetVersion?.trim() || undefined },
    }),
  )
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
