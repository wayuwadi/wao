import { ref, watch } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'system'

const THEME_KEY = 'wao-theme-mode-v1'

function resolveEffective(mode: ThemeMode): 'light' | 'dark' {
  if (mode !== 'system') return mode
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return 'light'
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function applyTheme(mode: ThemeMode) {
  const effective = resolveEffective(mode)
  document.documentElement.dataset.theme = effective
  document.documentElement.style.colorScheme = effective
}

function loadMode(): ThemeMode {
  try {
    const raw = localStorage.getItem(THEME_KEY)
    if (raw === 'light' || raw === 'dark' || raw === 'system') return raw
  } catch {
    /* ignore */
  }
  return 'system'
}

function saveMode(mode: ThemeMode) {
  try {
    localStorage.setItem(THEME_KEY, mode)
  } catch {
    /* ignore */
  }
}

const mode = ref<ThemeMode>(loadMode())

/** 当前实际生效的主题（system 已解析为 light/dark）。 */
const effective = ref<'light' | 'dark'>(resolveEffective(mode.value))

watch(
  mode,
  (next) => {
    applyTheme(next)
    saveMode(next)
    effective.value = resolveEffective(next)
  },
  { immediate: true },
)

/** 监听系统主题变化，当 mode 为 system 时自动切换。 */
if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
  const media = window.matchMedia('(prefers-color-scheme: dark)')
  media.addEventListener?.('change', () => {
    if (mode.value === 'system') {
      applyTheme('system')
      effective.value = resolveEffective('system')
    }
  })
}

export function useTheme() {
  function setTheme(next: ThemeMode) {
    mode.value = next
  }

  function toggleTheme() {
    const order: ThemeMode[] = ['light', 'dark', 'system']
    const idx = order.indexOf(mode.value)
    const next = order[(idx + 1) % order.length]
    mode.value = next ?? 'system'
  }

  return {
    mode,
    effective,
    setTheme,
    toggleTheme,
  }
}
