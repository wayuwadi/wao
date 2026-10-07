import { describe, expect, it } from 'vitest'
import { grantedPermissions, presetPermissions } from '@/utils/capabilityCenter'
import type { WorkspaceCapability, WorkspaceMcpToken } from '@/api/workspaceMcp'
const capabilities = [
  { name: 'list_my_pets', readOnly: true },
  { name: 'upload_pet', readOnly: false },
  { name: 'share_pet', readOnly: false },
].map((item) => ({ ...item, title: item.name, description: '', group: 'pets', cli: 'wao pets', mcp: null })) as WorkspaceCapability[]
describe('宠物设备授权独立于旧连接及日常预设', () => {
  it('旧读写连接不会获得 Pet 权限，新快照只保留明确授权项', () => {
    const old = { permissionMode: 'legacy', canWrite: true, canPublishHostedPage: true } as WorkspaceMcpToken
    expect(grantedPermissions(old, capabilities)).toEqual([])
    const scoped = { ...old, permissionMode: 'granular', permissions: ['upload_pet'] } as WorkspaceMcpToken
    expect(grantedPermissions(scoped, capabilities)).toEqual(['upload_pet'])
  })
  it('日常预设不会暗中授权 Pet 读取、上传或公开分享', () => {
    for (const preset of ['read', 'notes', 'projects', 'daily']) expect(presetPermissions(preset, capabilities)).toEqual([])
  })
})
