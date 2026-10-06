/** Shared project precedence; invalid explicit drafts stay unassigned for confirmation. */
export function selectTaskProject(ids: readonly string[], options: { draft?: string; context?: string; preferred?: string; recent?: string; requireConfirmation?: boolean }): string {
  const valid = (id?: string) => !!id && ids.includes(id)
  if (options.draft !== undefined) return valid(options.draft) ? options.draft : ''
  if (valid(options.context)) return options.context!
  if (options.requireConfirmation) return ''
  return [options.preferred, options.recent].find(valid) ?? ''
}
export const recentTaskProjectKey = (identity: string) => `wao-day-task-recent-project-v1:${identity}`
export function readRecentTaskProject(identity: string): string {
  try { return localStorage.getItem(recentTaskProjectKey(identity)) || '' } catch { return '' }
}
export function rememberTaskProject(identity: string, project: string): void {
  localStorage.setItem(recentTaskProjectKey(identity), project)
}
