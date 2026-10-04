import { computed, reactive, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { toast } from '@/composables/toast'

export type NoteImageMode = 'references' | 'embedded'
const modes = reactive<Record<string, NoteImageMode>>({})

/** 图片显示偏好独立于文档事实源与草稿，按成员在当前浏览器记忆。 */
export function useNoteImageMode() {
  const session = useSessionStore()
  const member = computed(() => session.currentUser.id || 'anonymous')
  watch(member, id => {
    try { modes[id] = localStorage.getItem(`wao-note-image-mode-v1:${id}`) === 'embedded' ? 'embedded' : 'references' }
    catch { modes[id] = modes[id] ?? 'references' }
  }, { immediate: true })
  return computed({
    get: () => modes[member.value] ?? 'references',
    set: (value: NoteImageMode) => {
      modes[member.value] = value
      try { localStorage.setItem(`wao-note-image-mode-v1:${member.value}`, value) }
      catch { toast('图片模式已切换，但浏览器未能记住，请再次切换以重试保存', 'ri-error-warning-line') }
    },
  })
}
