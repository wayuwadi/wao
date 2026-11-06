import { computed, ref, shallowRef, watch } from 'vue'
import { defineStore } from 'pinia'
import { fetchKbs, fetchSearchDocuments } from '@/api/board'
import { documentSearchText } from '@/utils/contentSearch'
import { useSessionStore } from './session'
import { useDataStore } from './data'
import type { Doc, KnowledgeBase } from '@/types'

/** Read-only index, separate from editors and their drafts. Cached only in this account's memory. */
export const useSearchDocumentsStore = defineStore('search-documents', () => {
  const session = useSessionStore()
  const data = useDataStore()
  const documents = shallowRef<{ doc: Doc; kb: KnowledgeBase; text: string }[]>([])
  const state = ref<'loading' | 'loaded' | 'error'>('loading')
  const refreshing = ref(false)
  let generation = 0,
    loadedAt = 0,
    pending: Promise<void> | null = null
  // A confirmed revision or directory change invalidates the snapshot, without reading editor drafts.
  watch(
    () =>
      JSON.stringify([
        Object.values(data.docs).map((doc) => [doc.id, doc.rev, doc.updated]),
        data.kbs.map((kb) => [kb.id, kb.name, kb.folders.map((folder) => folder.docs)]),
      ]),
    () => {
      if (!loadedAt) return
      const cached = new Map(documents.value.map((entry) => [entry.doc.id, entry]))
      const locations = new Map(
        data.kbs.flatMap((kb) =>
          kb.folders.flatMap((folder) => folder.docs.map((id) => [id, kb] as const)),
        ),
      )
      const changedDocument = Object.values(data.docs).some(
        (doc) =>
          locations.has(doc.id) &&
          (!cached.has(doc.id) || (doc.rev ?? 0) > (cached.get(doc.id)?.doc.rev ?? 0)),
      )
      const changedDirectory =
        data.loadStates.knowledge === 'loaded' &&
        documents.value.some(
          (entry) =>
            !locations.has(entry.doc.id) || locations.get(entry.doc.id)?.name !== entry.kb.name,
        )
      if (changedDocument || changedDirectory) loadedAt = 0
    },
    { flush: 'sync' },
  )
  const identity = computed(() => `${session.account?.accountId ?? ''}:${session.userId}`)
  watch(
    identity,
    () => {
      generation++
      loadedAt = 0
      pending = null
      documents.value = []
      state.value = 'loading'
      refreshing.value = false
    },
    { flush: 'sync' },
  )
  function load(force = false) {
    if (pending) return pending
    if (!force && state.value === 'loaded' && loadedAt && Date.now() - loadedAt < 30_000)
      return Promise.resolve()
    const current = generation
    refreshing.value = true
    state.value = 'loading'
    pending = (async () => {
      try {
        const [docs, kbs] = await Promise.all([fetchSearchDocuments(), fetchKbs()])
        if (current !== generation) return
        if (docs.some((doc) => doc.bodyLoaded === false)) throw new Error('正文读取不完整')
        const locations = new Map<string, KnowledgeBase>()
        for (const kb of kbs)
          for (const folder of kb.folders) for (const id of folder.docs) locations.set(id, kb)
        documents.value = docs.flatMap((doc) => {
          const kb = locations.get(doc.id)
          return kb ? [{ doc, kb, text: documentSearchText(doc) }] : []
        })
        loadedAt = Date.now()
        state.value = 'loaded'
      } catch {
        if (current === generation) state.value = 'error'
      } finally {
        if (current === generation) {
          pending = null
          refreshing.value = false
        }
      }
    })()
    return pending
  }
  return { documents, state, refreshing, identity, load }
})
