/** 清理已下线独立功能遗留在当前浏览器的草稿。 */
const LOCAL_PREFIXES = [
  'wao-action-plan-draft-v1',
  'wao-review-action-v1:',
  'wao-appearance-workspace-draft-v2:',
  'wao-appearance-form-draft-v2:',
  'wao-food-workspace-draft-v1',
  'wao-food-form-draft-v1',
  'wao-weekly-report-draft-v1:',
  'wao-workspaces:v2:',
  'wao:unified-memory-draft:',
]
const SESSION_PREFIXES = ['wao-ledger-draft:']

function clean(storage: Storage, prefixes: string[]) {
  const keys = Array.from({ length: storage.length }, (_, index) => storage.key(index))
  for (const key of keys) {
    if (key && prefixes.some((prefix) => key.startsWith(prefix))) storage.removeItem(key)
  }
}

export function retireUnusedStorage() {
  try { clean(localStorage, LOCAL_PREFIXES) } catch { /* 禁用浏览器存储时不影响启动。 */ }
  try { clean(sessionStorage, SESSION_PREFIXES) } catch { /* 同上。 */ }
}
