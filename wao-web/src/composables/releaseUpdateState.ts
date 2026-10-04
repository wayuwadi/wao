import { ref } from 'vue'
import { consumePendingReleaseVersion } from '@/utils/versionUpdate'
export const releaseJustUpdated = ref(false)
/** 安全刷新标记必须匹配当前制品；只轻提示，不再次弹出介绍。 */
export function completeReleaseUpdate() {
  const pending = consumePendingReleaseVersion()
  const current = document.querySelector('script[data-v]')?.getAttribute('data-v')
  if (pending && current && pending === current) releaseJustUpdated.value = true
}
