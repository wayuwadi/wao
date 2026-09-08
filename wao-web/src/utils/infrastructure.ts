import type { InfraService } from '@/api/infrastructure'

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
    && (state === 'all' || (state === 'running' ? infraRunning(s) : !infraRunning(s))))
    .sort((a, b) => (b.memoryBytes ?? -1) - (a.memoryBytes ?? -1))
}
