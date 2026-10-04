import { computed, reactive, watch } from 'vue'
import { useSessionStore } from '@/stores/session'

export type NoteWritingWidth = 'wide' | 'narrow'
const modes = reactive<Record<string, NoteWritingWidth>>({})

/** 同一成员的编辑器同步布局，正文及编辑器实例保持原样。 */
export function useNoteWritingWidth() {
  const session = useSessionStore()
  const member = computed(() => session.currentUser.id || 'anonymous')
  watch(member, id => {
    try { modes[id] = localStorage.getItem(`wao-note-writing-width-v1:${id}`) === 'narrow' ? 'narrow' : 'wide' }
    catch { modes[id] = modes[id] ?? 'wide' }
  }, { immediate: true })
  return computed({
    get: () => modes[member.value] ?? 'wide',
    set: (value: NoteWritingWidth) => {
      modes[member.value] = value
      try { localStorage.setItem(`wao-note-writing-width-v1:${member.value}`, value) }
      catch { /* 本机偏好写入失败不影响正文或当前布局。 */ }
    },
  })
}
