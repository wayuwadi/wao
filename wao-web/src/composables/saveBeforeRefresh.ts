import { onBeforeUnmount, onMounted } from 'vue'
/** Register an editor's ordinary save path; rejected saves keep the workspace open. */
export function useSaveBeforeRefresh(save: () => Promise<unknown>) {
  function handle(event: Event) {
    const detail = (event as CustomEvent<{ waitUntil: (promise: Promise<unknown>) => void }>).detail
    detail.waitUntil(save())
  }
  onMounted(() => window.addEventListener('wao-save-before-refresh', handle))
  onBeforeUnmount(() => window.removeEventListener('wao-save-before-refresh', handle))
}
