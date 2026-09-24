import type { KnowledgeBase } from '@/types'

export interface DirectoryNode { key: string; id: string; kind: 'folder' | 'doc'; parent: string; title: string; depth: number; hasChildren: boolean }

/** 兼容旧目录；新增节点自动采用原有分组归属。 */
export function directoryParents(kb: KnowledgeBase): Record<string, string> {
  const parents: Record<string, string> = {}
  for (const folder of kb.folders) {
    parents[`f:${folder.id}`] = ''
    for (const id of folder.docs) parents[`d:${id}`] = `f:${folder.id}`
  }
  const overrides = kb.directory ?? {}
  const order = kb.directoryOrder
  if (order?.length) {
    const ordered: Record<string, string> = {}
    for (const node of new Set([...order, ...Object.keys(parents)])) {
      if (!(node in parents)) continue
      const parent = overrides[node]
      ordered[node] = parent !== undefined && (!parent || parent in parents) ? parent : parents[node]!
    }
    return ordered
  }
  for (const [node, parent] of Object.entries(overrides)) {
    if (node in parents && (!parent || parent in parents)) parents[node] = parent
  }
  return parents
}

/** 扁平化只用于渲染，完整父子关系始终保留；搜索显示命中节点及祖先路径。 */
export function directoryRows(kb: KnowledgeBase, titleOf: (id: string) => string, open: Record<string, boolean>, query: string): DirectoryNode[] {
  const parents = directoryParents(kb)
  const nodes = Object.entries(parents).map(([key, parent]) => ({ key, parent, id: key.slice(2), kind: key.startsWith('f:') ? 'folder' as const : 'doc' as const,
    title: key.startsWith('f:') ? kb.folders.find(f => f.id === key.slice(2))?.name ?? '' : titleOf(key.slice(2)), depth: 0, hasChildren: false }))
  const children = new Map<string, DirectoryNode[]>()
  for (const node of nodes) children.set(node.parent, [...(children.get(node.parent) ?? []), node])
  const allowed = new Set<string>()
  const search = query.trim().toLocaleLowerCase()
  for (const node of nodes.filter(n => n.title.toLocaleLowerCase().includes(search))) {
    let cursor = node.key
    while (cursor && !allowed.has(cursor)) { allowed.add(cursor); cursor = parents[cursor] ?? '' }
  }
  const rows: DirectoryNode[] = []
  const visited = new Set<string>()
  function visit(parent: string, depth: number) {
    for (const node of children.get(parent) ?? []) {
      if (visited.has(node.key) || (search && !allowed.has(node.key))) continue
      visited.add(node.key)
      rows.push({ ...node, depth, hasChildren: !!children.get(node.key)?.length })
      if (search || open[node.key] !== false) visit(node.key, depth + 1)
    }
  }
  visit('', 0)
  return rows
}
