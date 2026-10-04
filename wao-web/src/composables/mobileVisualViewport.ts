import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { CSSProperties } from 'vue'
import { useIsMobile } from './viewport'

/** 可见视口与编辑焦点共同判断键盘；双指缩放不能触发键盘布局。 */
export function useMobileVisualViewport() {
  const mobile = useIsMobile()
  const frame = ref<{ height: number; top: number } | null>(null)
  const reduced = ref(false)
  const editing = ref(false)
  const viewport = typeof window !== 'undefined' ? window.visualViewport : null

  function update() {
    editing.value = document.activeElement instanceof Element
      && !!document.activeElement.closest('input:not([type="checkbox"]):not([type="radio"]), textarea, [contenteditable="true"]')
    if (!viewport || Math.abs(viewport.scale - 1) > 0.01) {
      frame.value = null
      reduced.value = false
      return
    }
    frame.value = { height: viewport.height, top: viewport.offsetTop }
    reduced.value = window.innerHeight - viewport.height > 120
  }
  onMounted(() => {
    update()
    viewport?.addEventListener('resize', update)
    viewport?.addEventListener('scroll', update)
    window.addEventListener('resize', update)
    document.addEventListener('focusin', update)
    document.addEventListener('focusout', update)
  })
  onBeforeUnmount(() => {
    viewport?.removeEventListener('resize', update)
    viewport?.removeEventListener('scroll', update)
    window.removeEventListener('resize', update)
    document.removeEventListener('focusin', update)
    document.removeEventListener('focusout', update)
  })
  const fullscreenStyle = computed<CSSProperties>(() => mobile.value && frame.value
    ? { '--note-visible-height': `${frame.value.height}px`, '--note-visible-top': `${frame.value.top}px` }
    : {})
  return { isMobile: mobile, fullscreenStyle, keyboardOpen: computed(() => mobile.value && editing.value && reduced.value) }
}
