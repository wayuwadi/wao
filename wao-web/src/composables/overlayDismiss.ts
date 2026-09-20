import { onScopeDispose, watch } from 'vue'

type Entry = { close: () => void | Promise<void> }
const stack: Entry[] = []
function onEscape(event: KeyboardEvent) {
  if (event.key !== 'Escape' || event.defaultPrevented || event.isComposing) return
  const top = stack[stack.length - 1]
  if (!top) return
  event.preventDefault()
  event.stopPropagation()
  void top.close()
}
/** 仅关闭最后打开的浮层；内部菜单可先 stopPropagation 消费 Esc。关闭函数负责保存及失败保留。 */
export function useOverlayDismiss(open: () => boolean, close: () => void | Promise<void>) {
  const entry = { close }
  const remove = () => {
    const index = stack.indexOf(entry)
    if (index >= 0) stack.splice(index, 1)
    if (!stack.length) document.removeEventListener('keydown', onEscape)
  }
  watch(open, active => {
    remove()
    if (active) {
      stack.push(entry)
      document.addEventListener('keydown', onEscape)
    }
  }, { immediate: true, flush: 'sync' })
  onScopeDispose(remove)
}
