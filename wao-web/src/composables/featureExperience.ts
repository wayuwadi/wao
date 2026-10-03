import { useRouter } from 'vue-router'
import { useUiStore } from '@/stores/ui'
import { MODULES } from '@/config/nav'
import { toast } from '@/composables/toast'

/** 只试用已登记的功能，保留用户所在页面与正在填写的表单。 */
export function useFeatureExperience() {
  const router = useRouter()
  const ui = useUiStore()
  return async (id: string) => {
    const module = Object.prototype.hasOwnProperty.call(MODULES, id) ? MODULES[id as keyof typeof MODULES] : undefined
    if (!module) return false
    if (ui.modal && !(id === 'notes' && ui.modal === 'note')) {
      toast('先保存或收起当前编辑，再试用新功能', 'ri-information-line')
      return false
    }
    if (id === 'notes') ui.openModal('note')
    else await router.push(id === 'home' ? { path: module.path, query: { try: 'inspiration' } } : module.path)
    return true
  }
}
