import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSessionStore } from '@/stores/session'
import { useWebPetStore } from '@/stores/webPet'
import { fetchPreference, savePreference } from '@/api/favorites'
import type { AuthSession } from '@/api/auth'
vi.mock('@/api/favorites', () => ({ fetchPreference: vi.fn(), savePreference: vi.fn() }))
const account = (id: string) => ({ member: { id } }) as AuthSession
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(r => { resolve = r })
  return { promise, resolve }
}
beforeEach(() => {
  setActivePinia(createPinia())
  vi.resetAllMocks()
  useSessionStore().account = account('a')
})
describe('宠物偏好请求隔离', () => {
  it('切换账号后旧读取不能污染新账号，旧缓存不复用', async () => {
    const old = deferred<string>()
    vi.mocked(fetchPreference).mockReturnValueOnce(old.promise).mockResolvedValueOnce('{"idleAnimation":false}')
    const pet = useWebPetStore()
    const first = pet.load()
    useSessionStore().account = account('b')
    await pet.load()
    old.resolve('{"idleAnimation":true}')
    await first
    expect(pet.settings.idleAnimation).toBe(false)
    expect(pet.loadState).toBe('loaded')
    expect(fetchPreference).toHaveBeenCalledTimes(2)
  })
  it('保存晚到的响应不能回灌切换后的账号', async () => {
    const old = deferred<void>()
    vi.mocked(savePreference).mockReturnValueOnce(old.promise)
    const pet = useWebPetStore()
    const saved = pet.save({ ...pet.settings, idleAnimation: false })
    const rejected = expect(saved).rejects.toThrow('账号已切换')
    useSessionStore().account = account('b')
    old.resolve()
    await rejected
    expect(pet.settings.idleAnimation).toBe(true)
    expect(pet.saveState).toBe('idle')
  })
  it('旧读取不能覆盖已成功保存的新设置', async () => {
    const old = deferred<string>()
    vi.mocked(fetchPreference).mockReturnValueOnce(old.promise)
    vi.mocked(savePreference).mockResolvedValue(undefined)
    const pet = useWebPetStore()
    const loading = pet.load()
    await pet.save({ ...pet.settings, idleAnimation: false })
    old.resolve('{"idleAnimation":true}')
    await loading
    expect(pet.settings.idleAnimation).toBe(false)
    expect(pet.loadState).toBe('loaded')
  })
})
