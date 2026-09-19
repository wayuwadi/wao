import { describe, it, expect } from 'vitest'
import { infraAttention, infraBytes, infraFilter, infraMemoryUsed, infraPercent } from '@/utils/infrastructure'
import type { InfraNode, InfraService } from '@/api/infrastructure'

describe('基础设施真实数据展示', () => {
  const healthy: InfraService = { name: 'app', group: 'WAO', image: '', kind: 'docker', state: 'running', status: 'Up (healthy)', cpuPercent: null, memoryBytes: null }
  const node: InfraNode = { id: 'a', name: 'a', role: '', address: '', online: true, stale: false, collectedAt: 1000, services: [healthy], history: [] }
  it('证据不足优先于服务判断，正常快照不添加问题', () => {
    expect(infraAttention(node, 1100)).toBeNull()
    expect(infraAttention({ ...node, error: 'offline' }, 1100)?.title).toContain('采集失败')
    expect(infraAttention({ ...node, collectedAt: undefined }, 1100)?.title).toContain('尚无')
    expect(infraAttention(node, 1300)?.title).toContain('快照需要更新')
    expect(infraAttention({ ...node, stale: true }, 1100)?.title).toContain('快照需要更新')
    expect(infraAttention({ ...node, warnings: ['无法采集'] }, 1100)?.title).toContain('采集提示')
    expect(infraAttention({ ...node, services: [] }, 1100)?.title).toContain('清单尚不可用')
  })
  it('待核对同时包含停止与仍运行但健康异常的服务', () => {
    const services = [healthy, { ...healthy, name: 'stopped', state: 'exited' }, { ...healthy, name: 'unhealthy', status: 'Up (unhealthy)' }]
    expect(infraFilter(services, '', 'review').map(s => s.name)).toEqual(['stopped', 'unhealthy'])
    expect(infraAttention({ ...node, services }, 1100)?.title).toBe('2 项服务待核对')
    expect(infraAttention({ ...node, services, error: 'offline' }, 1100)?.services).toBe(false)
  })
  it('未知数据不显示为零，并使用可用内存计算占比', () => {
    expect(infraBytes(null)).toBe('—')
    expect(infraPercent(undefined)).toBe('—')
    expect(infraBytes(1073741824)).toBe('1.0 GiB')
    expect(infraMemoryUsed({ total: 100, available: 30 })).toBe(70)
    expect(infraMemoryUsed(undefined)).toBeNull()
  })
  it('保留停止服务，按应用搜索并支持状态过滤', () => {
    const services: InfraService[] = [
      { name: 'jms_core', group: 'JumpServer', image: 'jumpserver/core:v4', kind: 'docker', state: 'running', status: 'Up', cpuPercent: 1, memoryBytes: 300 },
      { name: 'grafana', group: '监控组件', image: 'grafana:latest', kind: 'docker', state: 'exited', status: 'Exited', cpuPercent: null, memoryBytes: null },
    ]
    expect(infraFilter(services, '', 'all')).toHaveLength(2)
    expect(infraFilter(services, 'jumpserver', 'running').map(s => s.name)).toEqual(['jms_core'])
    expect(infraFilter(services, '', 'stopped').map(s => s.name)).toEqual(['grafana'])
    expect(infraFilter(services, 'unknown', 'all')).toHaveLength(0)
  })
})
