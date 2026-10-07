import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { createWorkspaceReloader } from '@/utils/workspaceReload'
import { consumeReleaseExperience, consumePendingReleaseVersion, rememberReleaseReload } from '@/utils/versionUpdate'

vi.mock('@/composables/toast', () => ({ toast: vi.fn() }))
const action = { featureId: 'settings', action: { target: 'settings-about', label: '看看关于 WAO' } }
const event = () => new CustomEvent('wao-workspace-reload', { detail: { targetVersion: 'new1234', experience: action } })
const cleanup: Array<() => void> = []
function listen(name: string, fn: EventListener) {
  window.addEventListener(name, fn)
  cleanup.push(() => window.removeEventListener(name, fn))
}
beforeEach(() => sessionStorage.clear())
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.restoreAllMocks() })

describe('更新并打开功能的保存屏障', () => {
  it('等待所有草稿与第二次工作区保存，串行执行一次，保存后才记录入口并刷新', async () => {
    let saved!: () => void
    const draft = new Promise<void>(resolve => { saved = resolve })
    listen('wao-save-before-refresh', e => (e as CustomEvent).detail.waitUntil(draft))
    const flush = vi.fn().mockResolvedValue(true), reload = vi.fn()
    const run = createWorkspaceReloader({ flush, reload, member: () => 'member-a' })
    const pending = run(event())
    await flushPromises()
    await run(event())
    expect(flush).toHaveBeenCalledTimes(1)
    expect(consumePendingReleaseVersion()).toBe('')
    expect(consumeReleaseExperience('new1234', 'member-a')).toBeNull()
    expect(reload).not.toHaveBeenCalled()
    saved(); await pending
    expect(flush).toHaveBeenCalledTimes(2)
    expect(reload).toHaveBeenCalledOnce()
    expect(consumePendingReleaseVersion()).toBe('new1234')
    expect(consumeReleaseExperience('new1234', 'member-a')).toEqual(action)
    expect(consumeReleaseExperience('new1234', 'member-a')).toBeNull()
  })
  it.each(['workspace', 'draft', 'second-flush', 'unload', 'member', 'storage', 'reload'])('%s 失败不跳走、不留下一次误跳请求，重试可成功', async cause => {
    let member = 'member-a'
    const flush = vi.fn().mockResolvedValue(true), reload = vi.fn()
    if (cause === 'workspace') flush.mockResolvedValueOnce(false)
    if (cause === 'second-flush') flush.mockResolvedValueOnce(true).mockResolvedValueOnce(false)
    if (cause === 'draft') listen('wao-save-before-refresh', e => (e as CustomEvent).detail.waitUntil(Promise.reject(new Error('草稿保存失败'))))
    if (cause === 'unload') listen('beforeunload', e => e.preventDefault())
    if (cause === 'member') flush.mockImplementationOnce(async () => { member = 'member-b'; return true })
    if (cause === 'storage') vi.spyOn(Storage.prototype, 'setItem').mockImplementationOnce(() => { throw new Error('存储不可用') })
    if (cause === 'reload') reload.mockImplementationOnce(() => { throw new Error('刷新失败') })
    const run = createWorkspaceReloader({ flush, reload, member: () => member })
    await run(event())
    expect(reload).toHaveBeenCalledTimes(cause === 'reload' ? 1 : 0)
    expect(consumeReleaseExperience('new1234', 'member-a')).toBeNull()
    expect(consumePendingReleaseVersion()).toBe('')
    cleanup.splice(0).forEach(fn => fn()); vi.restoreAllMocks()
    await run(event())
    expect(consumeReleaseExperience('new1234', member)).toEqual(action)
  })
  it('跨成员、过期、旧制品、非法入口均不误跳，普通刷新清除旧选择', () => {
    rememberReleaseReload('new1234', 'member-a', action)
    expect(consumeReleaseExperience('new1234', 'member-b')).toBeNull()
    rememberReleaseReload('new1234', 'member-a', action)
    expect(consumeReleaseExperience('old', 'member-a')).toBe('version-mismatch')
    rememberReleaseReload('new1234', 'member-a', action)
    vi.spyOn(Date, 'now').mockReturnValue(Date.now() + 11 * 60_000)
    expect(consumeReleaseExperience('new1234', 'member-a')).toBeNull()
    vi.restoreAllMocks()
    expect(() => rememberReleaseReload('new1234', 'member-a', {featureId:'settings',action:{target:'https://example.com',label:'错误入口'}})).toThrow()
    rememberReleaseReload('new1234', 'member-a', action)
    rememberReleaseReload('new1234', 'member-a')
    expect(consumeReleaseExperience('new1234', 'member-a')).toBeNull()
  })
})
