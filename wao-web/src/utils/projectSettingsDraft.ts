export interface ProjectSettingsValues { name: string; area: string; goal: string; icon: string; due: string; members: string[] }
export interface ProjectSettingsDraft { base: ProjectSettingsValues; value: ProjectSettingsValues; revision: string }
const key = (member: string, project: string) => `wao-project-settings-draft-v1:${member}:${project}`
function valid(value: unknown): value is ProjectSettingsValues {
  if (!value || typeof value !== 'object') return false
  const v = value as Record<string, unknown>
  return ['name', 'area', 'goal', 'icon', 'due'].every(field => typeof v[field] === 'string') && Array.isArray(v.members) && v.members.every(id => typeof id === 'string')
}
export function readProjectSettingsDraft(member: string, project: string): ProjectSettingsDraft | null {
  try {
    const draft = JSON.parse(localStorage.getItem(key(member, project)) ?? 'null')
    return valid(draft?.base) && valid(draft?.value) && typeof draft.revision === 'string' ? draft : null
  } catch { return null }
}
/** 只移除本窗口已保存的版本，避免清掉另一窗口刚写入的草稿。 */
export function writeProjectSettingsDraft(member: string, project: string, draft: ProjectSettingsDraft | null, revision?: string): boolean {
  try {
    if (draft) localStorage.setItem(key(member, project), JSON.stringify(draft))
    else if (!revision || readProjectSettingsDraft(member, project)?.revision === revision) localStorage.removeItem(key(member, project))
    return true
  } catch { return false }
}
