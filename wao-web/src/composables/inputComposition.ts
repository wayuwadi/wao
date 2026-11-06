/** 表单快捷键不能把输入法确认或取消当作提交、失焦或关闭。 */
export function useInputComposition() {
  let composing = false
  const active = (event?: KeyboardEvent) => composing || !!event?.isComposing || event?.keyCode === 229
  function guard(event: KeyboardEvent) {
    if (!active(event) || !['Enter', 'Escape'].includes(event.key)) return false
    event.preventDefault()
    event.stopPropagation()
    return true
  }
  return { active, guard, start: () => { composing = true }, end: () => { composing = false } }
}
