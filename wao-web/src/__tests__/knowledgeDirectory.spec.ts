import { describe, it, expect } from 'vitest'
import { directoryParents, directoryRows, groupLevelExpansion } from '@/utils/knowledgeDirectory'
import type { KnowledgeBase } from '@/types'
const kb: KnowledgeBase = { id: 'k', name: '知识库', icon: '', color: '', project: null, desc: '', folders: [
  { id: 'a', name: '分组 A', docs: ['x'] }, { id: 'b', name: '分组 B', docs: ['y'] },
], directory: { 'f:b': 'd:x' } }
describe('混合知识库目录', () => {
  it('旧目录保持原归属，多层目录保留分组和文档的混合路径', () => {
    expect(directoryParents({ ...kb, directory: undefined })).toEqual({ 'f:a': '', 'd:x': 'f:a', 'f:b': '', 'd:y': 'f:b' })
    expect(directoryRows(kb, id => id, {}, '').map(n => [n.key, n.depth])).toEqual([['f:a', 0], ['d:x', 1], ['f:b', 2], ['d:y', 3]])
  })
  it('默认展开通向分组的混合层级，分组中的文档按需显示', () => {
    const state = groupLevelExpansion(kb)
    expect(directoryRows(kb, id => id, state, '').map(node => node.key)).toEqual(['f:a', 'd:x', 'f:b'])
    state['f:b'] = true
    expect(directoryRows(kb, id => id, state, '').map(node => node.key)).toEqual(['f:a', 'd:x', 'f:b', 'd:y'])
  })
  it('收起文档隐藏子分组，搜索仍显示匹配节点的全部祖先', () => {
    expect(directoryRows(kb, id => id, { 'd:x': false }, '').map(n => n.key)).toEqual(['f:a', 'd:x'])
    expect(directoryRows(kb, id => id, { 'd:x': false }, 'y').map(n => n.key)).toEqual(['f:a', 'd:x', 'f:b', 'd:y'])
  })
  it('新节点和已删除目标的历史关系不会导致文档消失', () => {
    expect(directoryRows({ ...kb, directory: { 'd:x': 'f:deleted' } }, id => id, {}, '').map(n => n.key)).toContain('d:x')
  })
  it('完整目录快照使用显式顺序，旧关系快照仍按原有列表排序', () => {
    const ordered = { ...kb, directory: { 'f:a': '', 'f:b': '', 'd:y': 'f:b', 'd:x': 'f:a' } }
    expect(directoryRows(ordered, id => id, {}, '').map(n => n.key)).toEqual(['f:a', 'd:x', 'f:b', 'd:y'])
    ordered.directory = { 'f:b': '', 'd:y': 'f:b', 'f:a': '', 'd:x': 'f:a' }
    expect(directoryRows(ordered, id => id, {}, '').map(n => n.key)).toEqual(['f:a', 'd:x', 'f:b', 'd:y'])
    ordered.directory = { 'd:x': 'f:a', 'd:y': 'f:b', 'f:a': '', 'f:b': '' }
    expect(directoryRows({ ...ordered, directoryOrder: ['f:b', 'd:y', 'f:a', 'd:x'] }, id => id, {}, '').map(n => n.key))
      .toEqual(['f:b', 'd:y', 'f:a', 'd:x'])
    expect(directoryRows({ ...ordered, folders: [...ordered.folders, { id: 'c', name: '新分组', docs: [] }], directoryOrder: ['f:b', 'd:y', 'f:a', 'd:x'] }, id => id, {}, '').map(n => n.key))
      .toEqual(['f:b', 'd:y', 'f:a', 'd:x', 'f:c'])
  })
  it('大量同级文档保持顺序，标题只读取一次', () => {
    const docs = Array.from({ length: 12000 }, (_, index) => `doc-${index}`)
    let reads = 0
    const rows = directoryRows({ ...kb, folders: [{ id: 'large', name: '大目录', docs }], directory: undefined }, id => { reads++; return id }, {}, '')
    expect(rows).toHaveLength(12001)
    expect(rows[rows.length - 1]?.id).toBe('doc-11999')
    expect(reads).toBe(12000)
  })
  it('深层导入目录遍历与搜索不会耗尽调用栈', () => {
    const folders = Array.from({ length: 6000 }, (_, index) => ({ id: `f${index}`, name: `分组 ${index}`, docs: [] as string[] }))
    const directory = Object.fromEntries(folders.slice(1).map((folder, index) => [`f:${folder.id}`, `f:f${index}`]))
    const deep = { ...kb, folders, directory }
    const rows = directoryRows(deep, id => id, groupLevelExpansion(deep), '')
    expect(rows).toHaveLength(6000)
    expect(rows[rows.length - 1]?.depth).toBe(5999)
    expect(directoryRows(deep, id => id, {}, '分组 5999')).toHaveLength(6000)
  })
  it('搜索自动展开路径，但不改变传入的折叠选择', () => {
    const expansion = { 'f:a': false, 'd:x': false }
    expect(directoryRows(kb, id => id, expansion, 'Y').map(node => node.key)).toEqual(['f:a', 'd:x', 'f:b', 'd:y'])
    expect(expansion).toEqual({ 'f:a': false, 'd:x': false })
    expect(directoryRows(kb, id => id, expansion, '').map(node => node.key)).toEqual(['f:a'])
  })
  it('首页可保留命中文件夹和文档的所有后代，同时保留祖先路径', () => {
    const source = { ...kb, folders: [...kb.folders, { id: 'c', name: '无关分组', docs: ['z'] }] }
    expect(directoryRows(source, id => id, {}, '分组 B').map(node => node.key)).toEqual(['f:a', 'd:x', 'f:b'])
    expect(directoryRows(source, id => id, { 'f:a': false }, '分组 B', undefined, { includeMatchedDescendants: true }).map(node => node.key))
      .toEqual(['f:a', 'd:x', 'f:b', 'd:y'])
    expect(directoryRows(source, id => id, { 'd:x': false }, 'X', undefined, { includeMatchedDescendants: true }).map(node => node.key))
      .toEqual(['f:a', 'd:x', 'f:b', 'd:y'])
  })
  it('包含后代的检索不会因历史循环关系进入死循环', () => {
    const cyclic = { ...kb, directory: { 'f:a': 'd:x', 'd:x': 'f:a', 'f:b': '' } }
    expect(directoryRows(cyclic, id => id, {}, 'x', undefined, { includeMatchedDescendants: true })).toEqual([])
  })
})
