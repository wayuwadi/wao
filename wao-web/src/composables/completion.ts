import { onBeforeUnmount, ref } from 'vue'
import { useDataStore } from '@/stores/data'

/** 勾完成后任务留在原处的时间：给操作一个反应的余地，别刚点完就消失。 */
export const SETTLE_MS = 3000

/**
 * 任务完成态切换 + 收起缓冲。
 *
 * <p>凡是有完成勾选框的列表都用它：勾上之后任务先原地停留 {@link SETTLE_MS} 毫秒
 * （视觉上已打勾、已划线），到点才真正被「已完成」分组收走；期间再点一次取消完成，
 * 缓冲立即撤销。
 *
 * @return toggle（切换完成态）与 isSettling（是否处于缓冲期）
 */
export function useCompletionSettle() {
  const data = useDataStore()
  const settling = ref(new Set<string>())
  const timers = new Map<string, ReturnType<typeof setTimeout>>()

  function toggle(id: string) {
    const wasDone = data.taskById(id)?.status === '已完成'
    data.toggleComplete(id)
    clearTimeout(timers.get(id))
    timers.delete(id)
    if (wasDone) {
      settling.value.delete(id)
      return
    }
    settling.value.add(id)
    timers.set(
      id,
      setTimeout(() => {
        settling.value.delete(id)
        timers.delete(id)
      }, SETTLE_MS),
    )
  }

  /** 缓冲期内的已完成任务在列表里仍按「未完成」摆放。 */
  const isSettling = (id: string) => settling.value.has(id)

  onBeforeUnmount(() => {
    timers.forEach((t) => clearTimeout(t))
    timers.clear()
  })

  return { toggle, isSettling }
}
