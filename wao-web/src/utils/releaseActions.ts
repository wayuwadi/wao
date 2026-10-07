import actions from '@/data/releaseActions.json'
import type { ReleaseAction, ReleaseHighlight } from '@/types/featureExplorer'
import { MODULES } from '@/config/nav'

/** 清单只选择已知功能或登记的具体入口；登记错误时不退回无关页面。 */
export function canExperienceRelease(featureId: string, action?: ReleaseAction) {
  return Object.prototype.hasOwnProperty.call(MODULES, featureId) &&
    (action ? !!resolveReleaseAction(featureId, action) : featureId !== 'changelog')
}

/** 新版登记入口可能尚未存在于旧制品；只传递安全标识，刷新后再严格解析。 */
export function canRequestReleaseExperience(featureId: string, action?: ReleaseAction) {
  return Object.prototype.hasOwnProperty.call(MODULES, featureId) &&
    (action ? typeof action.target === 'string' && /^[a-z0-9-]{1,80}$/.test(action.target) &&
      typeof action.label === 'string' && !!action.label.trim() && action.label.length <= 80 &&
      (!isKnownReleaseActionTarget(action.target) || !!resolveReleaseAction(featureId, action)) : featureId !== 'changelog')
}

export function isKnownReleaseActionTarget(target: string) {
  return actions.some(item => item.id === target)
}

/** 外部清单仅选择登记入口，不能提供任意 URL、路径或执行动作。 */
export function resolveReleaseAction(featureId: string, action?: ReleaseAction) {
  if (!action || typeof action.label !== 'string' || !action.label.trim() || action.label.length > 80) return undefined
  return actions.find(item => item.id === action.target && item.featureId === featureId)
}

export function releaseActionLabel(item: ReleaseHighlight, fallback: string) {
  return resolveReleaseAction(item.featureId, item.action) ? item.action!.label : fallback
}
