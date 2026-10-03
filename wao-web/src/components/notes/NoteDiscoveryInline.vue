<script setup lang="ts">
import { ref, watch } from 'vue'
import { feedbackDiscovery, fetchDiscovery, discoveryHttpStatus, type Discovery } from '@/api/noteDiscoveries'
const props = defineProps<{ item: Discovery }>()
const emit = defineEmits<{
  changed: [item: Discovery]
  source: [itemId: string, docId: string, event: Event]
  expanded: [value: boolean]
}>()
const expanded = ref(false), busy = ref(false), error = ref(''), correcting = ref(false), correction = ref('')
watch(() => props.item.id, () => { expanded.value = false; error.value = ''; correcting.value = false; correction.value = '' })
function toggle() { expanded.value = !expanded.value; emit('expanded', expanded.value) }
async function save(patch: Parameters<typeof feedbackDiscovery>[1]) {
  if (busy.value) return
  const id = props.item.id
  busy.value = true; error.value = ''
  try {
    const result = await feedbackDiscovery(props.item, patch)
    if (props.item.id !== id) return
    emit('changed', result)
    if ('correction' in patch) correcting.value = false
  } catch (cause) {
    if (props.item.id !== id) return
    if (discoveryHttpStatus(cause) === 409) {
      try { const result = await fetchDiscovery(id); if (props.item.id === id) emit('changed', result) } catch { /* 输入继续保留。 */ }
      error.value = '这条发现已在其他窗口更新，已读取最新状态；你的补充仍保留，请再试一次。'
    } else error.value = '没有保存成功，请重试；你的输入仍保留。'
  } finally { busy.value = false }
}
</script>

<template>
  <article class="note-discovery" :class="{ expanded }" aria-label="顺手发现">
    <button class="discovery-heading" type="button" :aria-expanded="expanded" :aria-controls="`discovery-${item.id}`" @click="toggle">
      <i class="ri-sparkling-line" aria-hidden="true"></i><span>{{ item.headline }}</span><i :class="expanded ? 'ri-arrow-up-s-line' : 'ri-arrow-down-s-line'" aria-hidden="true"></i>
    </button>
    <div v-if="expanded" :id="`discovery-${item.id}`" class="discovery-detail">
      <small v-if="item.origin === 'LEGACY'">旧版生成内容 · 未经新发现流程验证</small>
      <p v-if="item.validity === 'INVALID' && !item.restricted">这条发现已不适用于当前进展，收藏仍保留。</p>
      <template v-if="!item.restricted">
        <p v-if="item.observation !== item.headline">{{ item.observation }}</p><p>{{ item.valueReason }}</p>
        <img v-if="item.imageUrl" :src="item.imageUrl" alt="旧版生成图片" class="legacy-image">
        <div class="discovery-evidence"><div v-for="(source, index) in item.evidence" :key="`${source.docId}:${index}`">
          <small v-if="source.sourceBasis === 'USER_BASELINE'">这段依据来自整理前保留的记录。</small><blockquote>{{ source.quote }}</blockquote><button type="button" @click="emit('source', item.id, source.docId, $event)">{{ source.title || '查看小记' }} <i class="ri-arrow-right-up-line" aria-hidden="true"></i></button>
        </div></div>
        <p v-if="item.optionalNextStep" class="next-step">可以试试：{{ item.optionalNextStep }}</p>
      </template>
      <p v-if="item.readOnly">旧服务只支持查看最近一次历史结果；服务更新后可查看全部历史与收藏。</p>
      <div v-if="!item.readOnly" class="discovery-actions">
        <button type="button" :disabled="busy" :aria-pressed="item.feedback.saved" @click="save({ saved: !item.feedback.saved })">{{ item.feedback.saved ? '取消收藏' : '收藏' }}</button>
        <button type="button" :disabled="busy" @click="save({ dismissed: !item.feedback.dismissed })">{{ item.feedback.dismissed ? '撤销不贴切' : '不贴切' }}</button>
        <button v-if="!item.restricted" type="button" :disabled="busy" @click="correcting = !correcting; correction = item.feedback.correction ?? correction">补充一句</button>
        <span v-if="busy" role="status">保存中…</span>
      </div>
      <small v-if="item.feedback.saved">收藏是保留这条发现，不代表确认结论正确。</small>
      <form v-if="correcting" class="correction" @submit.prevent="save({ correction: correction || null })">
        <label>补充（可选）<textarea v-model="correction" maxlength="500" rows="2" :disabled="busy" /></label>
        <button type="submit" :disabled="busy">{{ busy ? '保存中…' : '保存补充' }}</button>
      </form>
      <p v-if="error" role="alert" class="discovery-error">{{ error }}</p>
    </div>
  </article>
</template>

<style scoped>
.note-discovery { border: 1px solid color-mix(in srgb, var(--wao-primary) 20%, var(--wao-line)); border-radius: 12px; margin: 12px 0; background: color-mix(in srgb, var(--wao-primary-soft) 40%, var(--wao-bg-card)); }
.discovery-heading { display: flex; align-items: flex-start; gap: 9px; width: 100%; padding: 13px 14px; text-align: left; border: 0; background: transparent; color: var(--wao-text-1); line-height: 1.6; font-size: 13px; }
.discovery-heading span { flex: 1; min-width: 0; overflow-wrap: anywhere; }.discovery-heading i { color: var(--wao-primary-text); }
.discovery-detail { padding: 0 14px 14px; font-size: 13px; line-height: 1.8; overflow-wrap: anywhere; }.discovery-detail p { margin: 8px 0; }.discovery-detail small { color: var(--wao-text-faint); }
.discovery-evidence { display: grid; gap: 8px; margin: 12px 0; }.discovery-evidence > div { padding: 10px; background: var(--wao-bg-card); border-radius: 8px; }blockquote { margin: 0 0 5px; white-space: pre-wrap; }
button { cursor: pointer; }button:focus-visible, textarea:focus-visible { outline: 2px solid var(--wao-primary); outline-offset: 2px; }.discovery-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.discovery-actions button, .discovery-evidence button, .correction button { border: 1px solid var(--wao-line-control); padding: 6px 9px; min-height: 36px; border-radius: 7px; color: var(--wao-primary-text); background: var(--wao-bg-card); }
.next-step { color: var(--wao-text-2); }.legacy-image { max-width: 100%; max-height: 300px; object-fit: contain; }.correction { margin-top: 10px; }.correction label { display: grid; gap: 5px; }.correction textarea { width: 100%; box-sizing: border-box; border: 1px solid var(--wao-line-control); border-radius: 7px; padding: 8px; background: var(--wao-bg-card); color: var(--wao-text-1); }.discovery-error { color: var(--wao-danger); }
</style>
