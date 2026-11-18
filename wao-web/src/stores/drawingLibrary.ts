import type { LibraryItem, LibraryItems } from '@excalidraw/excalidraw/types'
import { defineStore } from 'pinia'
import { ref, shallowRef, watch } from 'vue'
import { fetchDrawingLibrary, updateDrawingLibrary } from '@/api/drawings'
import { useSessionStore } from '@/stores/session'

export function sameLibraryItem(a: LibraryItem, b: LibraryItem) {
  return a === b || (a.id === b.id && a.created === b.created && a.status === b.status &&
    a.name === b.name && a.elements.length === b.elements.length &&
    a.elements.every((element, index) => {
      const other = b.elements[index]
      return other?.id === element.id && other?.versionNonce === element.versionNonce
    }))
}

export const useDrawingLibraryStore = defineStore('drawingLibrary', () => {
  const session = useSessionStore()
  const items = shallowRef<LibraryItems>([])
  const error = ref('')
  const pending = ref(false)

  function newContext() {
    return {
      controller: new AbortController(),
      changes: new Map<string, LibraryItem | null>(),
      task: null as Promise<void> | null,
    }
  }
  let context = newContext()

  watch(() => [session.account?.accountId, session.userId], () => {
    context.controller.abort()
    context = newContext()
    items.value = []
    error.value = ''
    pending.value = false
  }, { flush: 'sync' })

  function withChanges(base: LibraryItems, changes: ReadonlyMap<string, LibraryItem | null>) {
    const additions = [...changes.values()].filter((item): item is LibraryItem => item !== null)
    return [...additions, ...base.filter(item => !changes.has(item.id))]
  }

  function sync() {
    if (!session.userId) return Promise.resolve()
    const active = context
    if (active.task) return active.task
    active.task = (async () => {
      try {
        do {
          const sent = new Map(active.changes)
          const signal = active.controller.signal
          const remote = sent.size
            ? await updateDrawingLibrary(
                [...sent.values()].filter((item): item is LibraryItem => item !== null),
                [...sent].filter(([, item]) => item === null).map(([id]) => id),
                signal,
              )
            : await fetchDrawingLibrary(signal)
          if (active !== context) return
          for (const [id, item] of sent) {
            if (active.changes.get(id) === item) active.changes.delete(id)
          }
          items.value = withChanges(remote, active.changes)
          pending.value = active.changes.size > 0
          error.value = ''
        } while (active.changes.size)
      } catch (cause) {
        if (active !== context) return
        const detail = cause as { response?: { status?: number; data?: { message?: string } } }
        error.value = detail.response?.status === 400 && detail.response.data?.message
          ? detail.response.data.message
          : pending.value ? '素材未同步到账号' : '账号素材库读取失败'
      } finally {
        active.task = null
      }
    })()
    return active.task
  }

  function change(previous: LibraryItems, next: LibraryItems) {
    if (!session.userId) return
    const before = new Map(previous.map(item => [item.id, item]))
    const remaining = new Set(next.map(item => item.id))
    const changes = new Map<string, LibraryItem | null>()
    for (const item of next) {
      const prior = before.get(item.id)
      if (!prior || !sameLibraryItem(prior, item)) changes.set(item.id, item)
    }
    for (const item of previous) {
      if (!remaining.has(item.id)) changes.set(item.id, null)
    }
    if (!changes.size) return
    for (const [id, item] of changes) context.changes.set(id, item)
    pending.value = true
    items.value = withChanges(items.value, changes)
    void sync()
  }

  return { items, error, pending, sync, change }
})
