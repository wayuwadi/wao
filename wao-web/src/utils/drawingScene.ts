/** Match the server's persisted scene fields, independent of JSON formatting/key order. */
export function sameDrawingContent(left: string, right: string): boolean {
  if (left === right) return true
  function content(value: string) {
    const scene = JSON.parse(value)
    return JSON.stringify({
      elements: scene.elements,
      background: scene.appState?.viewBackgroundColor ?? '#ffffff',
      files: scene.files ?? {},
    }, (_key, item) => {
      if (item && typeof item === 'object' && !Array.isArray(item)) {
        return Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]]))
      }
      return item
    })
  }
  try {
    return content(left) === content(right)
  } catch {
    return false
  }
}
