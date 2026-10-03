import { onScopeDispose, watch } from 'vue'

type Entry = { close: () => void | Promise<void>; outside?: (event: PointerEvent) => void }
const stack: Entry[] = []
function onEscape(event: KeyboardEvent) {
  if (event.key !== 'Escape' || event.defaultPrevented || event.isComposing) return
  const top = stack[stack.length - 1]
  if (!top) return
  event.preventDefault()
  event.stopPropagation()
  void top.close()
}
function onOutside(event: PointerEvent) {
  if (event.defaultPrevented) return
  // 一次事件只交给当时最上层，关闭后不继续穿透到下一层。
  stack[stack.length - 1]?.outside?.(event)
}
/** 仅关闭最后打开的浮层；内部菜单可先 stopPropagation 消费 Esc。关闭函数负责保存及失败保留。 */
export function useOverlayDismiss(open: () => boolean, close: () => void | Promise<void>, outside?: (event: PointerEvent) => void) {
  const entry = { close, outside }
  const remove = () => {
    const index = stack.indexOf(entry)
    if (index >= 0) stack.splice(index, 1)
    if (!stack.length) {
      document.removeEventListener('keydown', onEscape)
      document.removeEventListener('pointerdown', onOutside)
    }
  }
  watch(open, active => {
    remove()
    if (active) {
      stack.push(entry)
      document.addEventListener('keydown', onEscape)
      document.addEventListener('pointerdown', onOutside)
    }
  }, { immediate: true, flush: 'sync' })
  onScopeDispose(remove)
  return { isTopmost: () => stack[stack.length - 1] === entry }
}
