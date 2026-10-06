import { computed, reactive, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import { toast } from '@/composables/toast'

export interface NoteDisplayPreferences { distinguishSystem: boolean; systemColor: string }
const defaults: NoteDisplayPreferences = { distinguishSystem: true, systemColor: '#7c83b8' }
const values = reactive<Record<string, NoteDisplayPreferences>>({})
const color = (value: unknown) => typeof value === 'string' && /^#[a-f\d]{6}$/i.test(value) ? value : defaults.systemColor

/** 仅显示偏好：与图片模式一样，在当前浏览器按成员保存，不改系统来源或正文。 */
export function useNoteDisplayPreferences() {
  const session = useSessionStore()
  const member = computed(() => session.currentUser.id || 'anonymous')
  watch(member, id => {
    try {
      const saved = JSON.parse(localStorage.getItem(`wao-note-display-v1:${id}`) || '{}') as Partial<NoteDisplayPreferences> | null
      values[id] = { distinguishSystem: typeof saved?.distinguishSystem === 'boolean' ? saved.distinguishSystem : true, systemColor: color(saved?.systemColor) }
    } catch { values[id] = values[id] ?? { ...defaults } }
  }, { immediate: true })
  const settings = computed(() => values[member.value] ?? defaults)
  function update(patch: Partial<NoteDisplayPreferences>) {
    const next = { ...settings.value, ...patch }
    next.systemColor = color(next.systemColor)
    values[member.value] = next
    try { localStorage.setItem(`wao-note-display-v1:${member.value}`, JSON.stringify(next)) }
    catch { toast('显示已更新，但浏览器未能记住；可重新调整以重试保存', 'ri-error-warning-line') }
  }
  return { settings, update }
}
