import { computed, ref } from 'vue'
import { fetchPreference, savePreference } from '@/api/favorites'
import { ALL_QUICK_LINKS as DEFAULT_LINKS, type QuickLink } from '@/config/quickLinks'

const PREF_KEY = 'quick-links'
const PREF_VERSION = 2
const MAX_LINKS = 20
const REQUIRED_AFTER_LEGACY_MIGRATION = new Set(['wao-jenkins'])
const RETIRED_LINK_IDS = new Set(['wao-desktop-pet'])
const ID_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/
const ICON_PATTERN = /^ri-[a-z0-9-]+$/

interface QuickLinksPreference {
  version: number
  links: QuickLink[]
}

const links = ref<QuickLink[]>([])
const loaded = ref(false)
const loading = ref(false)
const loadError = ref('')
const saveError = ref('')

function cloneDefaults(): QuickLink[] {
  return DEFAULT_LINKS.map((link) => ({ ...link, keywords: [...link.keywords] }))
}

function normalizeLink(value: unknown): QuickLink {
  if (!value || typeof value !== 'object') throw new Error('快捷链接格式不正确')
  const source = value as Partial<QuickLink>
  const id = typeof source.id === 'string' ? source.id.trim() : ''
  const label = typeof source.label === 'string' ? source.label.trim() : ''
  const subtitle = typeof source.subtitle === 'string' ? source.subtitle.trim() : ''
  const icon = typeof source.icon === 'string' ? source.icon.trim() : ''
  const url = typeof source.url === 'string' ? source.url.trim() : ''
  const openMode = source.openMode === 'TAB' ? 'TAB' : 'NEW_PAGE'
  const keywords = Array.isArray(source.keywords)
    ? source.keywords
        .filter((item): item is string => typeof item === 'string')
        .map((item) => item.trim())
        .filter(Boolean)
    : []

  if (!ID_PATTERN.test(id) || id.length > 48) throw new Error(`快捷链接标识不正确：${id || '空'}`)
  if (!label || label.length > 40) throw new Error(`快捷链接名称不正确：${id}`)
  if (subtitle.length > 100) throw new Error(`快捷链接描述过长：${label}`)
  if (!ICON_PATTERN.test(icon) || icon.length > 64) throw new Error(`快捷链接图标不正确：${label}`)
  if (!isSafeUrl(url)) throw new Error(`快捷链接地址必须是站内路径或 HTTPS 地址：${label}`)
  return { id, label, subtitle, icon, url, openMode, keywords }
}

function isSafeUrl(value: string): boolean {
  if (value.startsWith('/') && !value.startsWith('//')) return true
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && !url.username && !url.password
  } catch {
    return false
  }
}

/**
 * 校验并规范化成员的快捷链接草稿，避免重复标识和不安全跳转进入左下角入口。
 */
export function canonicalizeQuickLinks(values: unknown[]): QuickLink[] {
  if (values.length > MAX_LINKS) throw new Error(`快捷链接不能超过 ${MAX_LINKS} 个`)
  const ids = new Set<string>()
  return values.filter((value) => !RETIRED_LINK_IDS.has((value as QuickLink)?.id)).map((value) => {
    const link = normalizeLink(value)
    if (ids.has(link.id)) throw new Error(`快捷链接标识不能重复：${link.id}`)
    ids.add(link.id)
    return link
  })
}

/**
 * 读取版本化偏好。旧数组只在首次迁移时补齐 Jenkins 入口；
 * v2 中用户明确删除后的空列表或自定义列表会原样保留。
 */
export function parseQuickLinksPreference(raw: string): QuickLink[] {
  if (!raw) return cloneDefaults()
  const parsed: unknown = JSON.parse(raw)
  if (Array.isArray(parsed)) {
    const migrated = canonicalizeQuickLinks(parsed)
    for (const defaultLink of DEFAULT_LINKS) {
      if (
        REQUIRED_AFTER_LEGACY_MIGRATION.has(defaultLink.id)
        && !migrated.some((link) => link.id === defaultLink.id)
      ) {
        migrated.push({ ...defaultLink, keywords: [...defaultLink.keywords] })
      }
    }
    return migrated
  }
  if (!parsed || typeof parsed !== 'object') throw new Error('快捷链接偏好格式不正确')
  const preference = parsed as Partial<QuickLinksPreference>
  if (preference.version !== PREF_VERSION || !Array.isArray(preference.links)) {
    throw new Error('快捷链接偏好版本不受支持')
  }
  return canonicalizeQuickLinks(preference.links)
}

/** 加载当前成员的左下角快捷链接；失败时保留安全默认值并暴露可重试状态。 */
async function load(force = false) {
  if ((loaded.value && !force) || loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    links.value = parseQuickLinksPreference(await fetchPreference(PREF_KEY))
  } catch (error) {
    links.value = cloneDefaults()
    loadError.value = error instanceof Error ? error.message : '快捷链接读取失败'
  } finally {
    loaded.value = true
    loading.value = false
  }
}

/** 校验并保存当前成员的完整快捷链接列表，成功后才更新正在使用的左下角入口。 */
async function save(next: QuickLink[]) {
  saveError.value = ''
  try {
    const canonical = canonicalizeQuickLinks(next)
    const preference: QuickLinksPreference = { version: PREF_VERSION, links: canonical }
    await savePreference(PREF_KEY, JSON.stringify(preference))
    links.value = canonical
  } catch (error) {
    saveError.value = error instanceof Error ? error.message : '快捷链接保存失败'
    throw error
  }
}

function resetToDefaults() {
  links.value = cloneDefaults()
}

export function useQuickLinks() {
  return {
    links: computed(() => links.value),
    loaded,
    loading,
    loadError,
    saveError,
    load,
    save,
    resetToDefaults,
    defaults: DEFAULT_LINKS,
  }
}
