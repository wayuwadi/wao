import type { InfraNode, InfraService } from '@/api/infrastructure'

/** 解释采集到的状态，不把进程运行等同于应用可用。 */
export function infraServiceState(service: InfraService): string {
  const labels: Record<string, string> = {
    created: '已创建，尚未启动', running: '运行中', paused: '已暂停',
    restarting: '正在重启', removing: '正在移除', exited: '已退出', dead: '无法运行',
    active: '已激活', inactive: '未运行', activating: '正在启动',
    deactivating: '正在停止', failed: '失败状态', reloading: '正在重新加载',
  }
  const label = labels[service.state.toLowerCase()] ?? '未识别状态'
  return infraRunning(service) && service.status.toLowerCase().includes('unhealthy')
    ? `${label} · 健康检查异常` : label
}

export function infraNeedsReview(service: InfraService): boolean {
  return !infraRunning(service) || service.status.toLowerCase().includes('unhealthy')
}
export function infraAttention(node: InfraNode, now: number) {
  if (node.error) return { title: '采集失败，先确认数据是否更新', detail: '采集失败不等于主机宕机；查看节点原因与最后成功时间，再刷新确认。', services: false }
  if (!node.collectedAt) return { title: '尚无成功快照', detail: '当前无法判断资源或服务状态。查看节点采集说明，稍后刷新。', services: false }
  if (node.stale || now - node.collectedAt > 180 || !node.online) return { title: '快照需要更新', detail: '当前数据不能代表此刻状态。先查看采集时间与原因，避免据此判断应用是否正常。', services: false }
  if (node.warnings?.length) return { title: `有 ${node.warnings.length} 条采集提示`, detail: '部分信息可能缺失；先查看节点提示，缺失数据不代表零占用。', services: false }
  const count = node.services?.filter(infraNeedsReview).length ?? 0
  if (count) return { title: `${count} 项服务待核对`, detail: '包含未运行或健康检查异常的服务；停止的容器可能是预期状态，需要结合用途判断。', services: true }
  if (!node.services?.length) return { title: '服务清单尚不可用', detail: '资源采集成功不能证明服务正常；查看节点说明和采集权限。', services: false }
  return null
}

export function infraBytes(value?: number | null): string {
  if (value == null || !Number.isFinite(value)) return '—'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  let n = value, index = 0
  while (n >= 1024 && index < units.length - 1) { n /= 1024; index++ }
  return `${n.toFixed(index > 1 ? 1 : 0)} ${units[index]}`
}
export function infraPercent(value?: number | null): string {
  return value == null || !Number.isFinite(value) ? '—' : `${value.toFixed(1)}%`
}
export function infraRunning(service: InfraService): boolean {
  return service.state === 'running' || service.state === 'active'
}
export function infraMemoryUsed(memory?: { total: number; available: number }): number | null {
  return memory && memory.total > 0 ? Math.max(0, Math.min(100, (1 - memory.available / memory.total) * 100)) : null
}
export function infraFilter(services: InfraService[], query: string, state: string) {
  const search = query.trim().toLowerCase()
  return services.filter(s => (!search || `${s.name} ${s.group} ${s.image}`.toLowerCase().includes(search))
    && (state === 'all' || (state === 'review' ? infraNeedsReview(s) : state === 'running' ? infraRunning(s) : !infraRunning(s))))
    .sort((a, b) => (b.memoryBytes ?? -1) - (a.memoryBytes ?? -1))
}
