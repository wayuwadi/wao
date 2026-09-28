export const WEB_UPDATE_EVENT = 'plugin_web_update_notice'
export const OPEN_RELEASE_HIGHLIGHTS_EVENT = 'wao-open-release-highlights'
const PENDING_RELEASE_VERSION_KEY = 'wao-pending-release-highlights-version'

export interface WebUpdateEventDetail {
  version?: string
}

export interface WorkspaceReloadDetail {
  targetVersion?: string
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

/** 更新介绍只消费一次本次刷新标记，普通首次登录或手动刷新不会自动弹出。 */
export function consumePendingReleaseVersion() {
  const version = sessionStorage.getItem(PENDING_RELEASE_VERSION_KEY) || ''
  sessionStorage.removeItem(PENDING_RELEASE_VERSION_KEY)
  return version
}

export function openReleaseHighlights() {
  window.dispatchEvent(new Event(OPEN_RELEASE_HIGHLIGHTS_EVENT))
}
