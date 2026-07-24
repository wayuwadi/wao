import { reactive } from 'vue'

/**
 * 全局 toast —— 顶部居中深色胶囊，2.6s 自动消失（对齐 handoff 规格）。
 * 轻量自建，避免为几个提示引入整包组件库。
 */
export interface ToastItem {
  id: number
  text: string
  icon: string
}

const state = reactive({ items: [] as ToastItem[] })
let seq = 0

export function useToastHost() {
  return state
}

/** 弹出一条 toast。 */
export function toast(text: string, icon = 'ri-information-line') {
  const id = ++seq
  state.items.push({ id, text, icon })
  window.setTimeout(() => {
    const i = state.items.findIndex((t) => t.id === id)
    if (i >= 0) state.items.splice(i, 1)
  }, 2600)
}
