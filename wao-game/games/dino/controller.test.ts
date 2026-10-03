// @vitest-environment jsdom
import { IDBFactory } from 'fake-indexeddb'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createGameMemory } from '../../host/useGameMemory'
import { MemoryStore } from '../../host/memory'
import { freshMemory } from './save'

afterEach(() => vi.unstubAllGlobals())
describe('页面记忆控制器', () => {
  it('无广播时仍通过事务拒绝过期页写入；读取前保留可恢复草稿', async () => {
    vi.stubGlobal('BroadcastChannel', undefined)
    const factory = new IDBFactory()
    const disk = new MemoryStore(factory)
    const first = createGameMemory(disk), other = createGameMemory(new MemoryStore(factory))
    await first.initialize(); await other.initialize()
    first.update({ ...freshMemory(), practiceComplete: true }); await first.flush()
    other.update({ ...freshMemory(), muted: false }); await other.flush()
    expect(other.status.value).toBe('conflict')
    expect(other.draft.value.muted).toBe(false)
    expect((await disk.all())[0]?.payload.practiceComplete).toBe(true)
    await other.readOther(1)
    expect(other.draft.value.practiceComplete).toBe(true)
    expect((await disk.backups())[0]?.original.payload.muted).toBe(false)
  })
  it('仅本次模式不写本机，返回本机需要显式预览后的版本', async () => {
    vi.stubGlobal('BroadcastChannel', undefined)
    const store = new MemoryStore(new IDBFactory()), memory = createGameMemory(store)
    await memory.initialize(); memory.update(freshMemory()); await memory.flush()
    await memory.sessionOnly(); memory.update({ ...freshMemory(), practiceComplete: true }); await memory.flush()
    expect((await store.all())[0]?.payload.practiceComplete).toBe(false)
    expect(memory.current().payload.practiceComplete).toBe(true)
    await memory.replace(memory.current(), 1, true)
    expect(memory.mode.value).toBe('local')
    expect((await store.all())[0]?.payload.practiceComplete).toBe(true)
    expect(await store.backups()).toHaveLength(1)
  })
})
