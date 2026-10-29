import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { IDBFactory } from 'fake-indexeddb'
import { readDrawingDraft, removeDrawingDraft, writeDrawingDraft } from '@/utils/drawingDrafts'

beforeEach(() => vi.stubGlobal('indexedDB', new IDBFactory()))
afterEach(() => vi.unstubAllGlobals())
const draft = {
  memberId: 'a',
  id: 'drawing',
  sceneJson: '{"elements":[]}',
  revision: 3,
  updatedAt: 1,
}

it('同一画板在不同成员下隔离，删除不影响其他成员', async () => {
  await writeDrawingDraft(draft)
  await writeDrawingDraft({ ...draft, memberId: 'b', sceneJson: 'another' })
  expect(await readDrawingDraft('a', draft.id)).toMatchObject(draft)
  await removeDrawingDraft('a', draft.id)
  expect(await readDrawingDraft('a', draft.id)).toBeNull()
  expect(await readDrawingDraft('b', draft.id)).toMatchObject({
    memberId: 'b',
    sceneJson: 'another',
  })
})

it('旧草稿仅在个人文件授权成功后迁移一次，共享画板不读取旧草稿', async () => {
  await new Promise<void>((resolve, reject) => {
    const request = indexedDB.open('wao-drawing-drafts', 1)
    request.onupgradeneeded = () => request.result.createObjectStore('drafts', { keyPath: 'id' })
    request.onerror = () => reject(request.error)
    request.onsuccess = () => {
      const db = request.result
      const transaction = db.transaction('drafts', 'readwrite')
      const { memberId: _owner, ...legacy } = draft
      transaction.objectStore('drafts').put(legacy)
      transaction.oncomplete = () => {
        db.close()
        resolve()
      }
    }
  })
  expect(await readDrawingDraft('b', draft.id)).toBeNull()
  expect(await readDrawingDraft('a', draft.id, true)).toMatchObject(draft)
  expect(await readDrawingDraft('b', draft.id, true)).toBeNull()
  await removeDrawingDraft('a', draft.id)
  expect(await readDrawingDraft('a', draft.id, true)).toBeNull()
})
