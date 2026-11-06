import { describe, expect, it } from 'vitest'
import {
  fileCategory,
  formatResourceBytes,
  matchesResource,
  resourceBytes,
  resourceCapacity,
  searchTerms,
  sortResources,
} from '@/utils/resourceLibrary'

describe('resourceLibrary', () => {
  it('按真实文件名和 MIME 分类，不把改过的展示名称当作文件扩展名', () => {
    expect(fileCategory({ name: 'REPORT.XLSX', contentType: '' })).toBe('sheet')
    expect(fileCategory({ name: '没有扩展名', contentType: 'image/png' })).toBe('media')
    expect(fileCategory({ name: '画面.mp4' })).toBe('media')
    expect(fileCategory({ name: 'readme.md' })).toBe('document')
    expect(fileCategory({ name: 'data.json' })).toBe('code')
    expect(fileCategory({ name: 'backup.tar.gz' })).toBe('archive')
    expect(fileCategory({ name: 'unknown.xyz' })).toBe('other')
  })

  it('多个关键词跨字段匹配，并兼容全角输入和大小写', () => {
    expect(matchesResource(searchTerms(' ＷＡＯ  方案 '), 'wao-项目', '技术方案.pdf')).toBe(true)
    expect(matchesResource(searchTerms('wao 缺失'), 'wao-项目', '技术方案.pdf')).toBe(false)
    expect(matchesResource(searchTerms('   '))).toBe(true)
  })

  it('优先使用精确大小，旧数据标记近似且未知大小不假装为零', () => {
    expect(resourceBytes({ byteSize: 1599, size: '2 KB' })).toBe(1599)
    expect(resourceBytes({ size: '1.5 MiB' })).toBe(1572864)
    expect(resourceBytes({ byteSize: NaN, size: '1,024 KB' })).toBe(1048576)
    expect(resourceBytes({ size: '未知' })).toBeNull()
    expect(resourceBytes({ size: '-2 MB' })).toBeNull()
    expect(
      resourceCapacity([{ byteSize: 1024, size: '1 KB' }, { size: '1 KB' }, { size: '未知' }]),
    ).toEqual({ bytes: 2048, known: 2, approximate: true, incomplete: true })
    expect(resourceCapacity([{ byteSize: 0, size: '0 B' }])).toEqual({
      bytes: 0,
      known: 1,
      approximate: false,
      incomplete: false,
    })
    expect(formatResourceBytes(1536)).toBe('1.5 KB')
  })

  it('名称自然排序、大小降序和未知值末尾保持稳定，不修改原列表', () => {
    const rows = [
      { name: '文件10', size: '1 KB' },
      { name: '文件2', size: '2 KB' },
      { name: '文件1', size: '?' },
      { name: '相同大小', size: '1 KB' },
    ]
    expect(sortResources(rows, 'recent', (item) => item.name)).toBe(rows)
    expect(sortResources(rows, 'name', (item) => item.name).map((item) => item.name)).toEqual([
      '文件1',
      '文件2',
      '文件10',
      '相同大小',
    ])
    expect(
      sortResources(rows, 'size', (item) => item.name, resourceBytes).map((item) => item.name),
    ).toEqual(['文件2', '文件10', '相同大小', '文件1'])
    expect(rows[0]!.name).toBe('文件10')
  })
})
