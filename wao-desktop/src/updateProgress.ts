export function updateProgress(downloaded: number, total?: number): number | undefined {
  if (!total || total <= 0) return undefined
  return Math.min(99, Math.max(0, Math.round((downloaded / total) * 100)))
}
