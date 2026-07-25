import { onBeforeUnmount, ref } from 'vue'

/**
 * 每隔一段时间自跳的「当前时间」。
 *
 * <p>相对时间文案（「刚刚」「3 分钟前」）不会自己变新——抽屉一直开着的话，
 * 时间会一直停在写入那一刻。让它按固定节奏走一下，文案就能跟上。
 *
 * @param intervalMs 刷新间隔，默认 30 秒（分钟级文案够用，也不至于空转太频繁）
 */
export function useNow(intervalMs = 30_000) {
  const now = ref(new Date())
  const timer = setInterval(() => (now.value = new Date()), intervalMs)
  onBeforeUnmount(() => clearInterval(timer))
  return now
}
