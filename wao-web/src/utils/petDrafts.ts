export interface PetDraft {
  identity?: { handle: string; displayName: string }
  mode: string
  slug: string
  name: string
  description: string
  version: number
  source: string
  license: string
  file: File | null
}
function database(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open('wao-pet-drafts', 1)
    request.onupgradeneeded = () => request.result.createObjectStore('drafts')
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}
async function transaction<T>(member: string, write: boolean, value?: PetDraft | null): Promise<T> {
  const db = await database()
  return new Promise((resolve, reject) => {
    const tx = db.transaction('drafts', write ? 'readwrite' : 'readonly'),
      store = tx.objectStore('drafts')
    const req = write
      ? value
        ? store.put(value, member)
        : store.delete(member)
      : store.get(member)
    tx.oncomplete = () => {
      db.close()
      resolve(req.result as T)
    }
    tx.onerror = () => {
      db.close()
      reject(tx.error)
    }
    tx.onabort = () => {
      db.close()
      reject(tx.error)
    }
  })
}
export const readPetDraft = (member: string) => transaction<PetDraft | undefined>(member, false)
export const writePetDraft = (member: string, draft: PetDraft) =>
  transaction<void>(member, true, draft)
export const clearPetDraft = (member: string) => transaction<void>(member, true, null)
