import actions from '@/data/releaseActions.json'
import type { ReleaseAction, ReleaseHighlight } from '@/types/featureExplorer'

/** 外部清单仅选择登记入口，不能提供任意 URL、路径或执行动作。 */
export function resolveReleaseAction(featureId: string, action?: ReleaseAction) {
  if (!action || typeof action.label !== 'string' || !action.label.trim() || action.label.length > 80) return undefined
  return actions.find(item => item.id === action.target && item.featureId === featureId)
}

export function releaseActionLabel(item: ReleaseHighlight, fallback: string) {
  return resolveReleaseAction(item.featureId, item.action) ? item.action!.label : fallback
}
