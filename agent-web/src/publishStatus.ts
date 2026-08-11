const labels: Record<string, string> = {
  DRAFT: '待完善',
  GENERATING: '生成文案中',
  WAITING_REVIEW: '等待人工确认',
  PUBLISHING: '发布中',
  PUBLISHED: '已发布',
  FAILED: '失败',
}

export function publishStatusLabel(status: string) {
  return labels[status] || status
}

/** 生成中和发布中需要轮询后端。 */
export function isPublishInFlight(status: string) {
  return status === 'GENERATING' || status === 'PUBLISHING'
}

/** 只有这三种状态允许人工改文案、换素材和换账号。 */
export function isPublishEditable(status: string) {
  return status === 'DRAFT' || status === 'WAITING_REVIEW' || status === 'FAILED'
}

/** 能否提交发布：状态允许，且平台确实有官方发布接口。 */
export function canSubmitPublish(status: string, publishSupported: boolean) {
  return publishSupported && (status === 'WAITING_REVIEW' || status === 'FAILED')
}

export function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
