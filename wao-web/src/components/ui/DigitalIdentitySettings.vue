<script setup lang="ts">
import { Popover } from 'ant-design-vue'
import WaoButton from '@/components/ui/WaoButton.vue'
import { computed, ref, useId, watch } from 'vue'
import {
  fetchDigitalLibrary,
  type DigitalLibrary,
  type IdentitySelection,
} from '@/api/digitalMembers'
import { useSessionStore } from '@/stores/session'
const props = defineProps<{
  modelValue: IdentitySelection
  disabled?: boolean
  execution?: 'manual' | 'save'
}>()
const emit = defineEmits<{ 'update:modelValue': [value: IdentitySelection] }>()
const session = useSessionStore(),
  open = ref(false),
  loading = ref(false),
  error = ref('')
const library = ref<DigitalLibrary | null>(null)
const radioName = `digital-identity-${useId()}`
let epoch = 0
const choices = computed(
  () =>
    library.value?.subjects.filter(
      (subject) =>
        subject.state === 'active' &&
        subject.looks.some((look) =>
          look.versions.some((version) => version.state === 'confirmed'),
        ),
    ) ?? [],
)
const selected = computed(() =>
  choices.value.find((subject) => subject.id === props.modelValue.subjectId),
)
const versions = computed(
  () =>
    selected.value?.looks.flatMap((look) =>
      look.versions
        .filter((version) => version.state === 'confirmed')
        .map((version) => ({ ...version, name: `${look.name} · 第 ${version.number} 版` })),
    ) ?? [],
)
const defaultName = computed(
  () =>
    library.value?.subjects.find((subject) => subject.id === library.value?.binding.subjectId)
      ?.name,
)
function mode(value: IdentitySelection['mode']) {
  emit('update:modelValue', value === 'explicit' ? { mode: value } : { mode: value })
}
function subjectChange(event: Event) {
  emit('update:modelValue', {
    mode: 'explicit',
    subjectId: (event.target as HTMLSelectElement).value,
  })
}
function versionChange(event: Event) {
  emit('update:modelValue', {
    ...props.modelValue,
    versionId: (event.target as HTMLSelectElement).value,
  })
}
async function load() {
  const current = ++epoch
  loading.value = true
  error.value = ''
  library.value = null
  try {
    const value = await fetchDigitalLibrary()
    if (current === epoch) library.value = value
  } catch (cause) {
    if (current === epoch) error.value = cause instanceof Error ? cause.message : '形象设置读取失败'
  } finally {
    if (current === epoch) loading.value = false
  }
}
watch(open, (value) => {
  if (value) void load()
})
watch(
  () => session.userId,
  () => {
    epoch++
    library.value = null
    open.value = false
    loading.value = false
    error.value = ''
  },
)
</script>
<template>
  <Popover v-model:open="open" trigger="click" placement="bottomRight">
    <template #content
      ><section class="identity-popover" aria-label="本次配图形象">
        <h3>本次配图形象</h3>
        <p>
          {{
            execution === 'save'
              ? '只影响本次保存后的新配图；现有图片保持原样。'
              : '只影响下一次手动配图；已排队的任务保留原选择。'
          }}
        </p>
        <p v-if="loading" role="status">正在读取形象…</p>
        <p v-else-if="error" class="error" role="alert">
          {{ error }} <WaoButton @click="load">重试</WaoButton>
        </p>
        <template v-else-if="library"
          ><label
            ><input
              type="radio"
              :name="radioName"
              :checked="modelValue.mode === 'default'"
              @change="mode('default')"
            /><span
              >本人默认<small>{{
                defaultName && library.binding.versionId ? `（${defaultName}）` : '（尚未设置）'
              }}</small></span
            ></label
          ><label
            ><input
              type="radio"
              :name="radioName"
              :checked="modelValue.mode === 'explicit'"
              @change="mode('explicit')"
            />本次指定</label
          >
          <div v-if="modelValue.mode === 'explicit'" class="choices">
            <p v-if="!choices.length">还没有已确认的形象，请先在图库确认一个版本。</p>
            <template v-else
              ><select
                :value="modelValue.subjectId || ''"
                aria-label="本次配图成员"
                @change="subjectChange"
              >
                <option disabled value="">选择成员</option>
                <option v-for="subject in choices" :key="subject.id" :value="subject.id">
                  {{ subject.name }}
                </option></select
              ><select
                :value="modelValue.versionId || ''"
                aria-label="本次配图形象版本"
                @change="versionChange"
              >
                <option disabled value="">选择已确认版本</option>
                <option v-for="version in versions" :key="version.id" :value="version.id">
                  {{ version.name }}
                </option>
              </select></template
            >
          </div>
          <label
            ><input
              type="radio"
              :name="radioName"
              :checked="modelValue.mode === 'none'"
              @change="mode('none')"
            />不出现具体人物</label
          >
          <p class="capability">{{ library.capability.message }}</p></template
        >
        <footer>
          <RouterLink to="/digital-members">管理数字成员图库</RouterLink
          ><WaoButton @click="open = false">收起</WaoButton>
        </footer>
      </section></template
    >
    <WaoButton
      type="button"
      class="identity-trigger"
      aria-label="配图形象设置"
      title="配图形象设置"
      :disabled="disabled"
    >
      <i class="ri-user-settings-line" aria-hidden="true"></i>
    </WaoButton>
  </Popover>
</template>
<style scoped>
.identity-trigger.wao-button {
  height: 26px;
  min-height: 26px;
  padding: 0;
  min-width: 28px;
  border: 1px solid var(--wao-divider);
  border-radius: var(--wao-radius-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-weak);
  cursor: pointer;
}
.identity-trigger:hover {
  color: var(--wao-primary-text);
  border-color: var(--wao-primary);
}
.identity-popover {
  width: min(310px, calc(100vw - 60px));
  color: var(--wao-text);
  font-size: 13px;
}
.identity-popover h3 {
  font-size: 15px;
  margin: 0 0 7px;
}
.identity-popover p {
  line-height: 1.6;
  font-size: 12px;
  color: var(--wao-text-3);
  margin: 7px 0 12px;
}
.identity-popover label {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 9px 0;
  min-height: 38px;
}
.identity-popover label input {
  accent-color: var(--wao-primary);
  flex-shrink: 0;
}
.identity-popover label > span {
  min-width: 0;
  overflow-wrap: anywhere;
}
small {
  display: block;
  font-size: var(--wao-font-caption);
  color: var(--wao-text-3);
}
.choices {
  display: grid;
  gap: 8px;
  margin: 0 0 8px 22px;
}
.choices select {
  width: 100%;
  min-width: 0;
  min-height: 38px;
  background: var(--wao-bg-sub);
  color: var(--wao-text);
  border: 1px solid var(--wao-divider);
  border-radius: var(--wao-radius-control);
  padding: 6px;
}
.identity-popover footer {
  display: flex;
  gap: 10px;
  justify-content: space-between;
  align-items: center;
  border-top: 1px solid var(--wao-divider);
  padding-top: 11px;
}
.identity-popover a {
  color: var(--wao-primary-text);
  font-size: 12px;
}
.identity-popover button {
  border: 0;
  background: transparent;
  color: var(--wao-primary-text);
  cursor: pointer;
}
.identity-popover .error {
  color: var(--wao-danger-strong);
}
@media (max-width: 650px) {
  .identity-trigger.wao-button {
    height: 44px;
    min-height: 44px;
    min-width: 44px;
  }
  .identity-popover label {
    min-height: 44px;
  }
  .identity-popover :deep(.wao-button) {
    min-height: 44px;
  }
  .choices select {
    font-size: 16px;
    min-height: 44px;
  }
}
</style>
