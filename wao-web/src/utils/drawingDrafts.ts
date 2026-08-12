export interface DrawingDraft {
  id: string
  revision: number
  sceneJson: string
  updatedAt: number
}

const DB_NAME = 'wao-drawing-drafts'
const STORE_NAME = 'drafts'
const DB_VERSION = 1

function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME, { keyPath: 'id' })
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

export async function readDrawingDraft(id: string): Promise<DrawingDraft | null> {
  const db = await database()
  try {
    return (await requestResult(db.transaction(STORE_NAME).objectStore(STORE_NAME).get(id))) ?? null
  } finally {
    db.close()
  }
}

export async function writeDrawingDraft(draft: DrawingDraft): Promise<void> {
  const db = await database()
  try {
    await requestResult(db.transaction(STORE_NAME, 'readwrite').objectStore(STORE_NAME).put(draft))
  } finally {
    db.close()
  }
}

export async function removeDrawingDraft(id: string): Promise<void> {
  const db = await database()
  try {
    await requestResult(db.transaction(STORE_NAME, 'readwrite').objectStore(STORE_NAME).delete(id))
  } finally {
    db.close()
  }
}
