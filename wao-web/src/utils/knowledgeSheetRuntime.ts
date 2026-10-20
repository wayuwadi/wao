// Share in-flight downloads; a failed preload must remain retryable.
function importRuntime() {
  return Promise.all([
      import('@univerjs/presets'), import('@univerjs/preset-sheets-core'), import('@univerjs/preset-sheets-core/locales/zh-CN'),
      import('@univerjs/preset-sheets-filter'), import('@univerjs/preset-sheets-filter/locales/zh-CN'),
      import('@univerjs/preset-sheets-sort'), import('@univerjs/preset-sheets-sort/locales/zh-CN'),
      import('@univerjs/preset-sheets-core/lib/index.css'), import('@univerjs/preset-sheets-filter/lib/index.css'), import('@univerjs/preset-sheets-sort/lib/index.css'),
    ])
}
let runtime: ReturnType<typeof importRuntime> | undefined
export function loadSheetRuntime() {
  return runtime ??= importRuntime().catch(error => { runtime = undefined; throw error })
}
