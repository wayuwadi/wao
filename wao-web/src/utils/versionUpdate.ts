export const WEB_UPDATE_EVENT = 'plugin_web_update_notice'

export interface WebUpdateEventDetail {
  version?: string
}

export function reloadToLatestVersion() {
  // App owns the save barrier for tasks, forms and beforeunload guards.
  window.dispatchEvent(new Event('wao-workspace-reload'))
}
