import type { TaskStep } from '@/types'

/** 编号由服务端分配，不算用户改动。 */
function comparable(sub: TaskStep) {
  const { number: _number, ...rest } = sub
  return JSON.stringify(rest)
}

/**
 * 子任务三方合并：保存遇到版本冲突时，把本地改动叠到服务端最新快照上，而不是让保存永久卡在失败。
 *
 * <p>以最近一次与服务端一致的快照为基线，逐条按稳定 id 判断：
 * <ul>
 *   <li>本地改过（或新增）的条目以本地为准；</li>
 *   <li>本地没动、服务端改过的条目取服务端；</li>
 *   <li>任一方删除且另一方未改动时删除；一方删除、另一方改动时保留改动，避免丢内容；</li>
 *   <li>顺序沿用本地，服务端新增的条目按服务端顺序排在最前（新建子任务本就插在最前）。</li>
 * </ul>
 * 父节点被删除的条目挂回根，保证结果仍是一棵合法的树。
 *
 * @param base   最近一次服务端确认的子任务
 * @param local  本地当前子任务
 * @param server 服务端最新子任务
 * @return 合并后的子任务（新数组，不修改入参）
 */
export function mergeSubtasks(base: TaskStep[], local: TaskStep[], server: TaskStep[]): TaskStep[] {
  const byId = (list: TaskStep[]) => new Map(list.filter((sub) => sub.id).map((sub) => [sub.id!, sub]))
  const baseMap = byId(base)
  const serverMap = byId(server)
  const localIds = new Set(local.map((sub) => sub.id).filter(Boolean))
  const changed = (sub: TaskStep | undefined, original: TaskStep | undefined) =>
    !!sub && (!original || comparable(sub) !== comparable(original))

  const merged: TaskStep[] = []
  for (const sub of local) {
    if (!sub.id) {
      merged.push(sub)
      continue
    }
    const original = baseMap.get(sub.id)
    const remote = serverMap.get(sub.id)
    const localChanged = changed(sub, original)
    if (!remote) {
      // 服务端删掉了：本地没改就跟着删，改过就保留
      if (!original || localChanged) merged.push(sub)
      continue
    }
    merged.push(localChanged ? sub : { ...remote })
  }
  const serverAdded: TaskStep[] = []
  for (const remote of server) {
    if (!remote.id || localIds.has(remote.id)) continue
    const original = baseMap.get(remote.id)
    // 本地删掉的：服务端没改就尊重本地删除
    if (original && !changed(remote, original)) continue
    serverAdded.push({ ...remote })
  }
  const result = [...serverAdded, ...merged]
  const parents = new Map(result.filter((sub) => sub.id).map((sub) => [sub.id!, sub.parentId || null]))
  return result.map((sub) => {
    if (!sub.parentId) return sub
    // 父节点已不存在，或两边分别调整层级后绕成环：挂回根
    const seen = new Set([sub.id])
    let parent: string | null = sub.parentId
    while (parent) {
      if (!parents.has(parent) || seen.has(parent)) return { ...sub, parentId: null }
      seen.add(parent)
      parent = parents.get(parent) ?? null
    }
    return sub
  })
}
