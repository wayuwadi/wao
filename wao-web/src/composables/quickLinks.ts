import { ref, computed } from 'vue'
import { fetchPreference, savePreference } from '@/api/favorites'
import { ALL_QUICK_LINKS as DEFAULT_LINKS, type QuickLink } from '@/config/quickLinks'

const PREF_KEY = 'quick-links'
const links = ref<QuickLink[]>([])
const loaded = ref(false)
const loading = ref(false)

async function load() {
  if (loaded.value) return
  loading.value = true
  try {
    const raw = await fetchPreference(PREF_KEY)
    if (raw) {
      const parsed = JSON.parse(raw)
      if (Array.isArray(parsed)) {
        links.value = parsed
      } else {
        links.value = [...DEFAULT_LINKS]
      }
    } else {
      links.value = [...DEFAULT_LINKS]
    }
  } catch {
    links.value = [...DEFAULT_LINKS]
  } finally {
    loaded.value = true
    loading.value = false
  }
}

async function save(next: QuickLink[]) {
  links.value = next
  await savePreference(PREF_KEY, JSON.stringify(next))
}

function resetToDefaults() {
  links.value = DEFAULT_LINKS.map((l) => ({ ...l }))
}

export function useQuickLinks() {
  return {
    links: computed(() => links.value),
    loaded,
    loading,
    load,
    save,
    resetToDefaults,
    defaults: DEFAULT_LINKS,
  }
}
