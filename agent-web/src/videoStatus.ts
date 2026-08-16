const labels: Record<string, string> = {
  SUBMITTING: '正在提交',
  QUEUED: '排队中',
  IN_PROGRESS: '生成中',
  DOWNLOADING: '正在保存视频',
  COMPLETED: '已完成',
  FAILED: '失败',
}

export function videoStatusLabel(status: string) {
  return labels[status] || status
}

export function isVideoInFlight(status: string) {
  return ['SUBMITTING', 'QUEUED', 'IN_PROGRESS', 'DOWNLOADING'].includes(status)
}

export function formatVideoSize(size: string) {
  const [width, height] = size.split('x').map(Number)
  const orientation = width > height ? '横屏' : '竖屏'
  return `${orientation} ${width}×${height}`
}
