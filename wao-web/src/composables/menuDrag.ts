import { ref, toRef } from 'vue'
import { useNavStore } from '@/stores/nav'
import { toast } from '@/composables/toast'
import type { BuiltinMenuGroupCode, MenuPlacement, NavItemId } from '@/config/nav'

/** 侧栏、底部与顶部共用一次菜单拖拽，普通文本和页签拖拽不会改变菜单。 */
export function useMenuDrag() {
  const nav = useNavStore()
  const dragId = toRef(nav, 'draggedMenuId')
  const dragOverId = ref<NavItemId | 'dock' | 'top' | null>(null)

  function onDragStart(id: NavItemId, event: DragEvent) {
    if (!nav.canMoveMenus) {
      event.preventDefault()
      return
    }
    dragId.value = id
    event.dataTransfer?.setData('text/plain', id)
    if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
  }

  function onDragOver(id: NavItemId | 'dock' | 'top') {
    if (dragId.value && dragId.value !== id) dragOverId.value = id
  }

  function onDragEnd() {
    dragId.value = null
    dragOverId.value = null
  }

  async function onDrop(placement: MenuPlacement, group: BuiltinMenuGroupCode, targetId?: NavItemId) {
    const from = dragId.value
    onDragEnd()
    if (!from || from === targetId || !nav.canMoveMenus) return
    try {
      await nav.moveMenu(from, placement, group, targetId)
      toast(placement === 'TOP' ? '菜单已移到顶部按钮区'
        : placement === 'DOCK' ? '菜单已移到底部图标区' : '菜单位置已保存', 'ri-check-line')
    } catch (error) {
      toast(error instanceof Error ? error.message : '菜单保存失败，请重新拖拽重试', 'ri-error-warning-line')
    }
  }

  function onDropDock(targetId?: NavItemId) {
    const group = dragId.value ? nav.menuConfig(dragId.value)?.group : undefined
    return onDrop('DOCK', group ?? 'PERSONAL', targetId)
  }

  function onDropTop(targetId?: NavItemId) {
    const group = dragId.value ? nav.menuConfig(dragId.value)?.group : undefined
    return onDrop('TOP', group ?? 'PERSONAL', targetId)
  }

  return { dragId, dragOverId, onDragStart, onDragOver, onDragEnd, onDrop, onDropDock, onDropTop }
}
