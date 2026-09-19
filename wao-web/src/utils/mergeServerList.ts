/** 仅用于 API 返回的 JSON 数据；保持未变化字段的引用，避免轮询触发无效的深层更新。 */
export function assignChangedFields<T extends object>(local: T, remote: T): void {
  for (const key of Object.keys(remote) as (keyof T)[]) {
    const next = remote[key]
    const previous = local[key]
    if (previous === next) continue
    if (previous !== null && next !== null
      && typeof previous === 'object' && typeof next === 'object'
      && JSON.stringify(previous) === JSON.stringify(next)) continue
    local[key] = next
  }
}

/** 按 id 线性匹配快照，保留本地顺序、未落库新建项和正在编辑的项目。 */
export function mergeServerList<T extends { id: string }>(
  local: T[],
  remote: T[],
  protectedIds: ReadonlySet<string> = new Set(),
  pendingIds: ReadonlySet<string> = new Set(),
): void {
  const byId = new Map(local.map(item => [item.id, item]))
  const remoteIds = new Set<string>()
  for (const item of remote) {
    remoteIds.add(item.id)
    const existing = byId.get(item.id)
    if (existing) {
      if (!protectedIds.has(item.id)) assignChangedFields(existing, item)
    } else {
      local.push(item)
      byId.set(item.id, item)
    }
  }
  let retained = 0
  for (let index = 0; index < local.length; index++) {
    const item = local[index]!
    if (remoteIds.has(item.id) || protectedIds.has(item.id) || pendingIds.has(item.id)) {
      if (retained !== index) local[retained] = item
      retained++
    }
  }
  if (local.length !== retained) local.length = retained
}
