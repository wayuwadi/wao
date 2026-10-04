import { computed, onBeforeUnmount, onMounted, ref, type Ref } from 'vue'
import { fetchCodexModels, type CodexModelCatalog } from '@/api/codexModels'

/** 目录刷新只校验已选能力，不替换成员草稿里的模型和深度。 */
export function useAiModelCatalog(
  draft: Ref<{ model: string; reasoningEffort: string } | null>,
  allowDefault = false,
) {
  const catalog = ref<CodexModelCatalog | null>(null),
    catalogError = ref(''),
    catalogLoading = ref(false)
  let generation = 0,
    interval: ReturnType<typeof setInterval> | undefined
  const selectedModel = computed(() =>
    catalog.value?.models.find((item) => item.id === (draft.value?.model || 'gpt-6-astra')),
  )
  const selectedEfforts = computed(() => selectedModel.value?.reasoningEfforts || [])
  const validationError = computed(() => {
    if (!draft.value) return ''
    if (!draft.value.model && !allowDefault) return '请选择处理模型'
    if (catalogError.value && !catalog.value)
      return '模型目录暂不可读取，已有选择保留；请刷新目录后保存'
    if (!catalog.value) return '正在确认模型目录，请稍后保存'
    if (!selectedModel.value)
      return '模型目录中找不到已选模型，已有选择保留；请刷新目录或明确选择可用模型'
    if (!selectedEfforts.value.includes(draft.value.reasoningEffort))
      return '当前模型不支持所选思考深度，请重新选择'
    return ''
  })
  async function loadModels() {
    const ticket = ++generation
    catalogLoading.value = true
    catalogError.value = ''
    try {
      const value = await fetchCodexModels()
      if (ticket === generation) catalog.value = value
    } catch {
      if (ticket === generation) catalogError.value = '模型目录暂不可读取，已有选择保留'
    } finally {
      if (ticket === generation) catalogLoading.value = false
    }
  }
  function reset() {
    generation++
    catalog.value = null
    catalogError.value = ''
    void loadModels()
  }
  onMounted(() => {
    void loadModels()
    interval = setInterval(() => void loadModels(), 60_000)
  })
  onBeforeUnmount(() => {
    generation++
    clearInterval(interval)
  })
  return {
    catalog,
    catalogError,
    catalogLoading,
    selectedEfforts,
    validationError,
    loadModels,
    reset,
  }
}
