import { computed, ref } from 'vue'
import {
  adjustFunctionScore,
  fetchFunctionHealth,
  recordFunctionOpen,
  type FunctionHealthItem,
  type FunctionHealthOverview,
} from '@/api/functionHealth'

const overview = ref<FunctionHealthOverview | null>(null)
const loading = ref(false)
const error = ref('')
const savingFeature = ref('')

export function useFunctionHealth() {
  const byId = computed(() =>
    Object.fromEntries((overview.value?.functions ?? []).map((item) => [item.featureId, item])),
  )

  async function load(force = false) {
    if (loading.value || (!force && overview.value)) return
    loading.value = true
    error.value = ''
    try {
      overview.value = await fetchFunctionHealth()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '功能健康度读取失败'
    } finally {
      loading.value = false
    }
  }

  async function record(featureId: string, source = 'PAGE') {
    try {
      await recordFunctionOpen(featureId, source)
    } catch {
      // 页面功能本身优先；统计失败由功能地图的重试状态承接，不能阻断导航。
    }
    await load(true)
  }

  async function adjust(featureId: string, delta: -1 | 1): Promise<FunctionHealthItem> {
    if (savingFeature.value) throw new Error('评分正在保存，请稍后再试')
    savingFeature.value = featureId
    error.value = ''
    try {
      const saved = await adjustFunctionScore(featureId, delta)
      if (overview.value) {
        overview.value = {
          ...overview.value,
          functions: overview.value.functions.map((item) =>
            item.featureId === featureId ? saved : item,
          ),
        }
      }
      return saved
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '评分保存失败，请重试'
      throw cause
    } finally {
      savingFeature.value = ''
    }
  }

  return { overview, byId, loading, error, savingFeature, load, record, adjust }
}
