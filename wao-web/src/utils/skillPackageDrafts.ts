interface PackageFile {
  file: File
  relativePath: string
}
interface PackageDraft {
  archive: File | null
  files: PackageFile[]
}
const queues = new Map<string, Promise<unknown>>()
async function access(key: string, write: boolean, value?: PackageDraft | null) {
  const db = await new Promise<IDBDatabase>((resolve, reject) => {
    const request = indexedDB.open('wao-skill-package-drafts', 1)
    request.onupgradeneeded = () => request.result.createObjectStore('drafts')
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
  return new Promise<PackageDraft | undefined>((resolve, reject) => {
    const tx = db.transaction('drafts', write ? 'readwrite' : 'readonly'),
      store = tx.objectStore('drafts')
    const request = write ? (value ? store.put(value, key) : store.delete(key)) : store.get(key)
    tx.oncomplete = () => {
      db.close()
      resolve(request.result as PackageDraft | undefined)
    }
    tx.onerror = tx.onabort = () => {
      db.close()
      reject(tx.error)
    }
  })
}
function enqueue(key: string, write: boolean, value?: PackageDraft | null) {
  const pending = (queues.get(key) ?? Promise.resolve())
    .catch(() => {})
    .then(() => access(key, write, value))
  queues.set(key, pending)
  void pending
    .finally(() => {
      if (queues.get(key) === pending) queues.delete(key)
    })
    .catch(() => {})
  return pending
}
export async function readSkillPackageDraft(key: string) {
  const saved = await enqueue(key, false)
  if (!saved) return undefined
  return {
    archive: saved.archive,
    files: saved.files.map(({ file, relativePath }) => {
      Object.defineProperty(file, 'webkitRelativePath', { configurable: true, value: relativePath })
      return file
    }),
  }
}
export const writeSkillPackageDraft = (key: string, archive: File | null, files: File[]) =>
  enqueue(key, true, {
    archive,
    files: files.map((file) => ({ file, relativePath: file.webkitRelativePath })),
  })
export const clearSkillPackageDraft = (key: string) => enqueue(key, true, null)
