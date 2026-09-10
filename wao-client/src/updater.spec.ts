import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createUpdater } from './updater'

const mocks = vi.hoisted(() => ({ check: vi.fn(), relaunch: vi.fn() }))
vi.mock('@tauri-apps/plugin-updater', () => ({ check: mocks.check }))
vi.mock('@tauri-apps/plugin-process', () => ({ relaunch: mocks.relaunch }))

describe('signed client updates', () => {
  beforeEach(() => vi.resetAllMocks())
  it('does not install or restart when a download finishes', async () => {
    const artifact = { version: '1.1.0', body: '改进', close: vi.fn(), download: vi.fn(async cb => { cb({ event: 'Started', data: { contentLength: 100 } }); cb({ event: 'Progress', data: { chunkLength: 100 } }) }), install: vi.fn() }
    mocks.check.mockResolvedValue(artifact)
    const updater = createUpdater()
    await updater.find()
    expect(updater.state.phase).toBe('available')
    await updater.download()
    expect(updater.state.phase).toBe('ready')
    expect(artifact.install).not.toHaveBeenCalled()
    expect(mocks.relaunch).not.toHaveBeenCalled()
    await updater.install()
    expect(artifact.install).toHaveBeenCalledOnce()
    expect(mocks.relaunch).toHaveBeenCalledOnce()
  })
  it('signature failure prevents installation and can retry', async () => {
    const artifact = { version: '1.1.0', close: vi.fn(), download: vi.fn().mockRejectedValue(new Error('invalid signature')), install: vi.fn() }
    mocks.check.mockResolvedValue(artifact)
    const updater = createUpdater()
    await updater.find(); await updater.download(); await updater.install()
    expect(updater.state.phase).toBe('error')
    expect(artifact.install).not.toHaveBeenCalled()
    await updater.find()
    expect(updater.state.phase).toBe('available')
  })
  it('deduplicates checks and download actions', async () => {
    let resolve!: (value: null) => void
    mocks.check.mockReturnValue(new Promise(r => { resolve = r }))
    const updater = createUpdater()
    const pending = updater.find()
    await updater.find()
    await Promise.resolve()
    expect(mocks.check).toHaveBeenCalledOnce()
    resolve(null); await pending
    expect(updater.state.phase).toBe('current')
  })
  it('keeps the current version usable on check failure', async () => {
    mocks.check.mockRejectedValue(new Error('offline'))
    const updater = createUpdater()
    await updater.find()
    expect(updater.state.phase).toBe('error')
    expect(updater.state.message).toContain('继续使用当前版本')
  })
})
