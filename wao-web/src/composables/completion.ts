import { onBeforeUnmount, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { useUiStore } from '@/stores/ui'
import { toast } from '@/composables/toast'

/** 勾完成后条目留在原处的时间：给操作一个反应的余地，别刚点完就消失。 */
export const SETTLE_MS = 3000

/**
 * 「刚勾完成」的缓冲期跟踪器。
 *
 * <p>只管记录哪些 id 还在缓冲期内，不关心它们是任务还是子任务——任务列表和
 * 任务详情里的子任务列表都会把已完成项收走，需要同一套缓冲行为。
 *
 * @param ms 缓冲时长，默认 {@link SETTLE_MS}
 */
export function useSettle(ms = SETTLE_MS) {
  const settling = ref(new Set<string>())
  const timers = new Map<string, ReturnType<typeof setTimeout>>()

  /** 进入缓冲期；到点自动解除。 */
  function begin(id: string) {
    clearTimeout(timers.get(id))
    settling.value.add(id)
    timers.set(
      id,
      setTimeout(() => {
        settling.value.delete(id)
        timers.delete(id)
      }, ms),
    )
  }

  /** 立即解除缓冲（取消完成时用）。 */
  function cancel(id: string) {
    clearTimeout(timers.get(id))
    timers.delete(id)
    settling.value.delete(id)
  }

  /** 缓冲期内的已完成条目在列表里仍按「未完成」摆放。 */
  const isSettling = (id: string) => settling.value.has(id)

  onBeforeUnmount(() => {
    timers.forEach((t) => clearTimeout(t))
    timers.clear()
  })

  return { begin, cancel, isSettling }
}

/**
 * 任务完成态切换 + 收起缓冲。
 *
 * <p>凡是有完成勾选框的任务列表都用它：勾上之后任务先原地停留 {@link SETTLE_MS}
 * 毫秒（视觉上已打勾、已划线），到点才真正被「已完成」分组收走；期间再点一次
 * 取消完成，缓冲立即撤销。
 *
 * @return toggle（切换完成态）与 isSettling（是否处于缓冲期）
 */
export function useCompletionSettle() {
  const data = useDataStore()
  const ui = useUiStore()
  const { begin, cancel, isSettling } = useSettle()

  function toggle(id: string) {
    const t = data.taskById(id)
    if (!t) return
    const wasDone = t.status === '已完成'
    const title = t.title
    data.toggleComplete(id)
    if (wasDone) {
      cancel(id)
      return
    }
    begin(id)
    toast(`已完成「${title}」`, 'ri-checkbox-circle-fill')
    // 完成一个任务和创建一个任务同样值得庆祝
    ui.celebrate()
  }

  return { toggle, isSettling }
}
