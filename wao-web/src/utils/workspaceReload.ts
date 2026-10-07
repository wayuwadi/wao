import { nextTick } from 'vue'
import { toast } from '@/composables/toast'
import { clearReleaseReload, rememberReleaseReload, reportWorkspaceReloadState, type WorkspaceReloadDetail } from './versionUpdate'

/** 所有更新入口共用保存屏障；保存失败、会话切换或离开拦截时保留页面。 */
export function createWorkspaceReloader(options: {
  flush: () => Promise<boolean>
  member: () => string
  reload?: () => void
}) {
  let refreshing = false
  const reload = options.reload ?? (() => window.location.reload())
  return async (event?: Event) => {
    if (refreshing) return
    refreshing = true
    const detail = (event as CustomEvent<WorkspaceReloadDetail> | undefined)?.detail
    const member = options.member()
    reportWorkspaceReloadState({ state: 'saving' })
    try {
      if (document.activeElement instanceof HTMLElement) document.activeElement.blur()
      await nextTick()
      if (!(await options.flush())) throw new Error('保存失败，已保留当前页面和草稿，请重试')
      const requests: Promise<unknown>[] = []
      window.dispatchEvent(new CustomEvent('wao-save-before-refresh', {
        detail: { waitUntil: (request: Promise<unknown>) => requests.push(request) },
      }))
      await Promise.all(requests)
      if (!(await options.flush())) throw new Error('保存失败，已保留当前页面和草稿，请重试')
      if (!window.dispatchEvent(new Event('beforeunload', { cancelable: true })))
        throw new Error('当前编辑尚未保存，请处理页面中的保存提示后重试')
      if (member !== options.member()) throw new Error('登录状态已变化，请重新读取更新说明')
      // 入口与版本同时写入；存储失败不会刷新后丢失用户选择。
      rememberReleaseReload(detail?.targetVersion || '', member, detail?.experience)
      reportWorkspaceReloadState({ state: 'reloading' })
      reload()
    } catch (error) {
      clearReleaseReload()
      const message = error instanceof Error ? error.message : '保存失败，当前内容已保留'
      reportWorkspaceReloadState({ state: 'failed', message })
      toast(message, 'ri-error-warning-line')
    } finally { refreshing = false }
  }
}
