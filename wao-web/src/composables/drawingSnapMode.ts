import { computed, reactive } from 'vue'

export type DrawingSnapMode = 'objects' | 'grid' | 'off'

const modes = reactive(new Map<string, DrawingSnapMode>())

export function useDrawingSnapMode(memberId: string) {
  const key = `wao:drawing-snap-mode:${memberId}`
  if (!modes.has(memberId)) {
    let saved: string | null = null
    try {
      saved = localStorage.getItem(key)
    } catch {
      // The current session still supports changing modes when storage is unavailable.
    }
    modes.set(memberId, saved === 'grid' || saved === 'off' ? saved : 'objects')
  }

  return computed({
    get: (): DrawingSnapMode => modes.get(memberId) ?? 'objects',
    set: (mode: DrawingSnapMode) => {
      modes.set(memberId, mode)
      try {
        localStorage.setItem(key, mode)
      } catch {
        // Keep the in-memory preference for the current session.
      }
    },
  })
}
