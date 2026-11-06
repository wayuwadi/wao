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
    directoryVersion = '',
    pending: Promise<void> | null = null
  const directoryMetadata = () => data.kbs.map(kb =>
    [kb.id, kb.name, kb.folders.map(folder => [...folder.docs])])
  // Watch metadata only, batched per Vue update. A synchronous whole-catalog watcher turns
  // each of N catalog writes into another N-document scan and blocks large workspaces.
  watch(
    () => [
        Object.values(data.docs).map((doc) => [doc.id, doc.rev, doc.updated]),
        directoryMetadata(),
        data.loadStates.knowledge,
      ],
    reconcileMetadata,
  )
  function reconcileMetadata() {
    if (!documents.value.length && !loadedAt) return
    const cached = new Map(documents.value.map((entry) => [entry.doc.id, entry]))
    const locations = new Map(
      data.kbs.flatMap((kb) =>
        kb.folders.flatMap((folder) => folder.docs.map((id) => [id, kb] as const)),
      ),
    )
    const changedDocument = Object.values(data.docs).some(
      (doc) => locations.has(doc.id) &&
        (!cached.has(doc.id) || (doc.rev ?? 0) > (cached.get(doc.id)?.doc.rev ?? 0)),
    )
    if (changedDocument) loadedAt = 0
    if (data.loadStates.knowledge !== 'loaded') return
    const version = JSON.stringify(directoryMetadata())
    // The search endpoint may be newer than the page's catalog. Only a subsequent local
    // catalog change can authoritatively remove a hit from that newer server snapshot.
    if (version === directoryVersion) return
    directoryVersion = version
    const changedDirectory = locations.size !== cached.size || documents.value.some(
      (entry) => locations.get(entry.doc.id)?.id !== entry.kb.id ||
        locations.get(entry.doc.id)?.name !== entry.kb.name,
    )
    if (changedDirectory) {
      loadedAt = 0
      // Permission revocation, deletion and archival remove hits immediately, even if the
      // next network read fails. Locally retained drafts never re-enter this read-only index.
      documents.value = documents.value.flatMap(entry => {
        const kb = locations.get(entry.doc.id)
        return kb ? [{ ...entry, kb }] : []
      })
    }
  }
  const identity = computed(() => `${session.account?.accountId ?? ''}:${session.userId}`)
  watch(
    identity,
    () => {
      generation++
      loadedAt = 0
      directoryVersion = ''
      pending = null
      documents.value = []
      state.value = 'loading'
      refreshing.value = false
    },
    { flush: 'sync' },
  )
  function load(force = false) {
    if (pending) return pending
    // A caller can refresh in the same turn as a save, before the batched watcher runs.
    reconcileMetadata()
    if (!force && state.value === 'loaded' && loadedAt && Date.now() - loadedAt < 30_000)
      return Promise.resolve()
    const current = generation
    const requestedDirectory = JSON.stringify(directoryMetadata())
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
        const entries: typeof documents.value = []
        for (let index = 0; index < docs.length; index++) {
          if (current !== generation) return
          const doc = docs[index]!
          const kb = locations.get(doc.id)
          if (kb) entries.push({ doc, kb, text: documentSearchText(doc) })
          // Let input, paint and account changes run between batches of large libraries.
          if ((index + 1) % 24 === 0 && index + 1 < docs.length)
            await new Promise<void>(resolve => setTimeout(resolve, 0))
        }
        if (current !== generation) return
        documents.value = entries
        directoryVersion = requestedDirectory
        loadedAt = Date.now()
        state.value = 'loaded'
        reconcileMetadata()
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
