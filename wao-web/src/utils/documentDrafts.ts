import type { RichDocContent } from '@/types'

export interface DocumentDraft {
  id: string
  memberId: string
  title: string
  content: RichDocContent
  baseRev: number
  updatedAt: number
}

const DB_NAME = 'wao-document-drafts'
const STORE_NAME = 'drafts'
const DB_VERSION = 1

function key(memberId: string, id: string) {
  return `${memberId}:${id}`
}

function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME, { keyPath: 'key' })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('打开文档本机草稿失败'))
  })
}

function result<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('文档本机草稿操作失败'))
  })
}

export async function readDocumentDraft(memberId: string, id: string): Promise<DocumentDraft | null> {
  const db = await database()
  try {
    const row = await result(db.transaction(STORE_NAME).objectStore(STORE_NAME).get(key(memberId, id)))
    if (!row) return null
    const { key: _key, ...draft } = row as DocumentDraft & { key: string }
    return draft
  } finally {
    db.close()
  }
}

export async function writeDocumentDraft(draft: DocumentDraft): Promise<void> {
  // 恢复路径可能传入 Pinia/Vue 的嵌套 Proxy。文档契约本身是 JSON，
  // 在持久化边界创建普通数据快照，避免 IndexedDB 的结构化克隆拒绝 Proxy。
  const row = JSON.parse(JSON.stringify({ ...draft, key: key(draft.memberId, draft.id) }))
  const db = await database()
  try {
    await result(db.transaction(STORE_NAME, 'readwrite').objectStore(STORE_NAME).put(row))
  } finally {
    db.close()
  }
}

export async function removeDocumentDraft(memberId: string, id: string): Promise<void> {
  const db = await database()
  try {
    await result(db.transaction(STORE_NAME, 'readwrite').objectStore(STORE_NAME).delete(key(memberId, id)))
  } finally {
    db.close()
  }
}
