import { describe, it, expect } from 'vitest'
import { directoryParents, directoryRows } from '@/utils/knowledgeDirectory'
import type { KnowledgeBase } from '@/types'
const kb: KnowledgeBase = { id: 'k', name: '知识库', icon: '', color: '', project: null, desc: '', folders: [
  { id: 'a', name: '分组 A', docs: ['x'] }, { id: 'b', name: '分组 B', docs: ['y'] },
], directory: { 'f:b': 'd:x' } }
describe('混合知识库目录', () => {
  it('旧目录保持原归属，多层目录保留分组和文档的混合路径', () => {
    expect(directoryParents({ ...kb, directory: undefined })).toEqual({ 'f:a': '', 'd:x': 'f:a', 'f:b': '', 'd:y': 'f:b' })
    expect(directoryRows(kb, id => id, {}, '').map(n => [n.key, n.depth])).toEqual([['f:a', 0], ['d:x', 1], ['f:b', 2], ['d:y', 3]])
  })
  it('收起文档隐藏子分组，搜索仍显示匹配节点的全部祖先', () => {
    expect(directoryRows(kb, id => id, { 'd:x': false }, '').map(n => n.key)).toEqual(['f:a', 'd:x'])
    expect(directoryRows(kb, id => id, { 'd:x': false }, 'y').map(n => n.key)).toEqual(['f:a', 'd:x', 'f:b', 'd:y'])
  })
  it('新节点和已删除目标的历史关系不会导致文档消失', () => {
    expect(directoryRows({ ...kb, directory: { 'd:x': 'f:deleted' } }, id => id, {}, '').map(n => n.key)).toContain('d:x')
  })
})
