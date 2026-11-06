import type { FileItem } from '@/types'

export type ResourceSort = 'recent' | 'name' | 'size'
export type FileCategory =
  'all' | 'document' | 'sheet' | 'presentation' | 'archive' | 'media' | 'code' | 'other'

export const fileCategories: { id: FileCategory; label: string }[] = [
  { id: 'all', label: '全部文件' },
  { id: 'document', label: '文档' },
  { id: 'sheet', label: '表格' },
  { id: 'presentation', label: '演示文稿' },
  { id: 'archive', label: '压缩包' },
  { id: 'media', label: '图片与音视频' },
  { id: 'code', label: '代码与数据' },
  { id: 'other', label: '其他' },
]

export function fileCategory(file: Pick<FileItem, 'name' | 'contentType'>): FileCategory {
  const extension = file.name.split('.').pop()?.toLowerCase() ?? ''
  const mime = file.contentType?.toLowerCase() ?? ''
  if (
    /^(?:png|jpe?g|gif|webp|avif|svg|bmp|heic|tiff?|ico|mp[34]|mov|webm|avi|mkv|wav|ogg|flac|m4a)$/.test(
      extension,
    ) ||
    /^(?:image|audio|video)\//.test(mime)
  )
    return 'media'
  if (/^(?:xlsx?|csv|tsv|ods)$/.test(extension) || /spreadsheet|excel|csv/.test(mime))
    return 'sheet'
  if (/^(?:pptx?|odp|key)$/.test(extension) || /presentation|powerpoint/.test(mime))
    return 'presentation'
  if (/^(?:zip|rar|7z|tar|gz|bz2|xz|tgz)$/.test(extension) || /zip|compressed|x-tar/.test(mime))
    return 'archive'
  if (
    /^(?:pdf|docx?|odt|rtf|txt|md|markdown|epub|pages)$/.test(extension) ||
    /pdf|word|opendocument.text/.test(mime)
  )
    return 'document'
  if (
    /^(?:json|jsonl|xml|ya?ml|toml|ini|sql|js|jsx|ts|tsx|py|java|c|h|cpp|cs|go|rs|rb|sh|ps1|html?|css|vue)$/.test(
      extension,
    ) ||
    /json|javascript|xml/.test(mime)
  )
    return 'code'
  return 'other'
}

export function searchTerms(query: string): string[] {
  return query.normalize('NFKC').trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
}

export function matchesResource(
  terms: readonly string[],
  ...fields: (string | null | undefined)[]
): boolean {
  return matchesResourceText(terms, normalizedResourceText(...fields))
}

export function normalizedResourceText(...fields: (string | null | undefined)[]): string {
  return fields.filter(Boolean).join(' ').normalize('NFKC').toLocaleLowerCase()
}

export function matchesResourceText(terms: readonly string[], text: string): boolean {
  return terms.every((term) => text.includes(term))
}

/** Prefer precise server bytes; older servers only expose a rounded display value. */
export function resourceBytes(resource: { byteSize?: number | null; size: string }): number | null {
  if (
    typeof resource.byteSize === 'number' &&
    Number.isFinite(resource.byteSize) &&
    resource.byteSize >= 0
  )
    return resource.byteSize
  const match = resource.size.trim().match(/^([\d,.]+)\s*(B|KB|MB|GB|TB|KiB|MiB|GiB|TiB)$/i)
  if (!match) return null
  const value = Number(match[1]!.replace(/,/g, ''))
  const unit = match[2]!.toUpperCase().replace('I', '')
  const exponent = ['B', 'KB', 'MB', 'GB', 'TB'].indexOf(unit)
  return Number.isFinite(value) && value >= 0 ? value * 1024 ** exponent : null
}

export function formatResourceBytes(bytes: number): string {
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value.toLocaleString('zh-CN', { maximumFractionDigits: unit ? 1 : 0 })} ${units[unit]}`
}

export function resourceCapacity(resources: readonly { byteSize?: number | null; size: string }[]) {
  let bytes = 0
  let known = 0
  let approximate = false
  for (const resource of resources) {
    const value = resourceBytes(resource)
    if (value === null) continue
    bytes += value
    known++
    if (
      typeof resource.byteSize !== 'number' ||
      !Number.isFinite(resource.byteSize) ||
      resource.byteSize < 0
    )
      approximate = true
  }
  return { bytes, known, approximate, incomplete: known < resources.length }
}

const names = new Intl.Collator('zh-CN', { numeric: true, sensitivity: 'base' })

/** The API's newest-first order is authoritative; ties always keep that order. */
export function sortResources<T>(
  items: T[],
  sort: ResourceSort,
  name: (item: T) => string,
  bytes?: (item: T) => number | null,
): T[] {
  if (sort === 'recent') return items
  return items
    .map((item, index) => ({ item, index }))
    .sort((a, b) => {
      if (sort === 'name') return names.compare(name(a.item), name(b.item)) || a.index - b.index
      const left = bytes?.(a.item) ?? -1,
        right = bytes?.(b.item) ?? -1
      return right - left || a.index - b.index
    })
    .map(({ item }) => item)
}
