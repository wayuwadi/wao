import type { KnowledgeBase } from '@/types'

export interface DirectoryNode { key: string; id: string; kind: 'folder' | 'doc'; parent: string; title: string; depth: number; hasChildren: boolean }
export interface DirectorySearchOptions { includeMatchedDescendants?: boolean }

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

/** 默认展开通向分组的路径，分组内的文档由用户按需展开。 */
export function groupLevelExpansion(kb: KnowledgeBase): Record<string, boolean> {
  const parents = directoryParents(kb)
  const state: Record<string, boolean> = Object.fromEntries(Object.keys(parents).map(key => [key, false]))
  for (const key of Object.keys(parents).filter(key => key.startsWith('f:'))) {
    let parent = parents[key]
    while (parent && state[parent] !== true) {
      state[parent] = true
      parent = parents[parent]
    }
  }
  return state
}

/** 扁平化只用于渲染，完整父子关系始终保留；搜索显示命中节点及祖先路径。 */
export function directoryRows(kb: KnowledgeBase, titleOf: (id: string) => string, open: Record<string, boolean>, query: string, compareDocs?: (leftId: string, rightId: string) => number, options: DirectorySearchOptions = {}): DirectoryNode[] {
  const parents = directoryParents(kb)
  const folders = new Map(kb.folders.map(folder => [folder.id, folder.name]))
  const nodes = Object.entries(parents).map(([key, parent]) => ({ key, parent, id: key.slice(2), kind: key.startsWith('f:') ? 'folder' as const : 'doc' as const,
    title: key.startsWith('f:') ? folders.get(key.slice(2)) ?? '' : titleOf(key.slice(2)), depth: 0, hasChildren: false }))
  const children = new Map<string, DirectoryNode[]>()
  for (const node of nodes) {
    const siblings = children.get(node.parent)
    if (siblings) siblings.push(node)
    else children.set(node.parent, [node])
  }
  if (compareDocs) for (const siblings of children.values()) siblings.sort((left, right) =>
    left.kind === 'doc' && right.kind === 'doc' ? compareDocs(left.id, right.id) : 0)
  const allowed = new Set<string>()
  const search = query.trim().toLocaleLowerCase()
  const matches = search ? nodes.filter(node => node.title.toLocaleLowerCase().includes(search)) : []
  for (const node of matches) {
    let cursor = node.key
    while (cursor && !allowed.has(cursor)) { allowed.add(cursor); cursor = parents[cursor] ?? '' }
  }
  if (options.includeMatchedDescendants && matches.length) {
    const pending = [...matches]
    const expanded = new Set<string>()
    while (pending.length) {
      const node = pending.pop()!
      if (expanded.has(node.key)) continue
      expanded.add(node.key)
      allowed.add(node.key)
      for (const child of children.get(node.key) ?? []) pending.push(child)
    }
  }
  const rows: DirectoryNode[] = []
  const visited = new Set<string>()
  // Iterative traversal keeps deeply nested imported directories off the JS call stack.
  const stack = (children.get('') ?? []).map(node => ({ node, depth: 0 })).reverse()
  while (stack.length) {
    const { node, depth } = stack.pop()!
    if (visited.has(node.key) || (search && !allowed.has(node.key))) continue
    visited.add(node.key)
    const descendants = children.get(node.key) ?? []
    rows.push({ ...node, depth, hasChildren: descendants.length > 0 })
    if (search || open[node.key] !== false) {
      for (let i = descendants.length - 1; i >= 0; i--) stack.push({ node: descendants[i]!, depth: depth + 1 })
    }
  }
  return rows
}
