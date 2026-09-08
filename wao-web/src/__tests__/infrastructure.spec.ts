import { describe, it, expect } from 'vitest'
import { infraBytes, infraFilter, infraMemoryUsed, infraPercent } from '@/utils/infrastructure'
import type { InfraService } from '@/api/infrastructure'

describe('基础设施真实数据展示', () => {
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
