import { useRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { MODULES } from '@/config/nav'
import { toast } from '@/composables/toast'
import { resolveReleaseAction } from '@/utils/releaseActions'
import type { ReleaseAction } from '@/types/featureExplorer'

/** 只试用已登记的功能，保留用户所在页面与正在填写的表单。 */
export function useFeatureExperience() {
  const router = useRouter()
  const ui = useUiStore()
  return async (id: string, action?: ReleaseAction) => {
    const module = Object.prototype.hasOwnProperty.call(MODULES, id) ? MODULES[id as keyof typeof MODULES] : undefined
    if (!module) return false
    const destination = resolveReleaseAction(id, action)
    if (action && !destination) return false
    if (ui.modal && !(id === 'notes' && ui.modal === 'note')) {
      toast('先保存或收起当前编辑，再试用新功能', 'ri-information-line')
      return false
    }
    if (destination && destination.id !== 'notes-compose') {
      // 当前小记快捷编辑不得被更具体的体验入口替换。
      if (ui.modal) { toast('先保存或收起当前编辑，再试用新功能', 'ri-information-line'); return false }
      await router.push({ path: destination.path,
        query: { ...(router.currentRoute.value.path === destination.path ? router.currentRoute.value.query : {}),
          ...destination.query, ...(destination.query.releaseAction ? { releaseRequest: `${Date.now()}-${++releaseRequest}` } : {}) },
        hash: 'hash' in destination ? destination.hash : '' })
    }
    else if (id === 'notes') ui.openModal('note')
    else await router.push(module.path)
    return true
  }
}

let releaseRequest = 0
