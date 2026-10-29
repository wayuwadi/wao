export interface DrawingDraft {
  id: string
  memberId: string
  revision: number
  sceneJson: string
  updatedAt: number
}

const DB_NAME = 'wao-drawing-drafts'
const STORE_NAME = 'member-drafts'
const DB_VERSION = 2

function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME, { keyPath: 'key' })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('打开草图本机草稿失败'))
  })
}

function requestResult<T>(request: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error ?? new Error('草图本机草稿操作失败'))
  })
}

const key = (memberId: string, id: string) => JSON.stringify([memberId, id])

function completed(transaction: IDBTransaction): Promise<void> {
  return new Promise((resolve, reject) => {
    transaction.oncomplete = () => resolve()
    transaction.onerror = transaction.onabort = () =>
      reject(transaction.error ?? new Error('本机草稿未能写入'))
  })
}

export async function readDrawingDraft(
  memberId: string,
  id: string,
  allowLegacy = false,
): Promise<DrawingDraft | null> {
  const db = await database()
  try {
    const row = await requestResult(
      db.transaction(STORE_NAME).objectStore(STORE_NAME).get(key(memberId, id)),
    )
    if (row) return row
    // Only a successfully authorized personal drawing establishes the owner of a
    // legacy draft. Shared drawings must never adopt another member's old draft.
    if (allowLegacy && db.objectStoreNames.contains('drafts')) {
      const legacy = await requestResult(db.transaction('drafts').objectStore('drafts').get(id))
      if (legacy) {
        const draft = { ...legacy, memberId }
        const transaction = db.transaction([STORE_NAME, 'drafts'], 'readwrite')
        const done = completed(transaction)
        transaction.objectStore(STORE_NAME).put({ ...draft, key: key(memberId, id) })
        transaction.objectStore('drafts').delete(id)
        await done
        return draft
      }
    }
    return null
  } finally {
    db.close()
  }
}

export async function writeDrawingDraft(draft: DrawingDraft): Promise<void> {
  const db = await database()
  try {
    const transaction = db.transaction(STORE_NAME, 'readwrite')
    const done = completed(transaction)
    transaction.objectStore(STORE_NAME).put({ ...draft, key: key(draft.memberId, draft.id) })
    await done
  } finally {
    db.close()
  }
}

export async function removeDrawingDraft(memberId: string, id: string): Promise<void> {
  const db = await database()
  try {
    const transaction = db.transaction(STORE_NAME, 'readwrite')
    const done = completed(transaction)
    transaction.objectStore(STORE_NAME).delete(key(memberId, id))
    await done
  } finally {
    db.close()
  }
}
