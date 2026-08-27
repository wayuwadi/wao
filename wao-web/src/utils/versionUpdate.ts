export const WEB_UPDATE_EVENT = 'plugin_web_update_notice'

export interface WebUpdateEventDetail {
  version?: string
}

export function reloadToLatestVersion() {
  window.location.reload()
}
