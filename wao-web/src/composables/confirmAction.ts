import { shallowRef, watch } from 'vue'
import { useSessionStore } from '@/stores/session'

export interface ConfirmAction {
  title: string
  description: string
  confirmLabel?: string
  danger?: boolean
  run: () => void | Promise<void>
}

/** 确认前不执行操作；切换账号即丢弃原账号的确认请求。 */
export function useConfirmAction() {
  const session = useSessionStore()
  const confirmation = shallowRef<ConfirmAction | null>(null)
  watch(() => session.userId, () => { confirmation.value = null }, { flush: 'sync' })
  function confirmAction(action: ConfirmAction) {
    if (!confirmation.value) confirmation.value = action
  }
  function dismissConfirmation() { confirmation.value = null }
  return { confirmation, confirmAction, dismissConfirmation }
}
