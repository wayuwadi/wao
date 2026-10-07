<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useOverlayDismiss } from '@/composables/overlayDismiss'
const props = withDefaults(
  defineProps<{
    title: string
    description?: string
    width?: string
    height?: string
    embedded?: boolean
    theme?: 'light' | 'dark'
  }>(),
  { description: '', width: '1000px', height: 'min(760px, calc(100dvh - 48px))', embedded: false },
)
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLElement>()
const trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null
const dismiss = useOverlayDismiss(
  () => !props.embedded,
  () => emit('close'),
)
function outside() {
  if (dismiss.isTopmost()) emit('close')
}
function trap(event: KeyboardEvent) {
  if (props.embedded) return
  if (event.key !== 'Tab') return
  const items = [
    ...(dialog.value?.querySelectorAll<HTMLElement>(
      'button:not(:disabled), select:not(:disabled), input:not(:disabled), textarea:not(:disabled), [tabindex="0"]',
    ) ?? []),
  ].filter((item) => !item.closest('[hidden]'))
  const first = items[0],
    last = items[items.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last?.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first?.focus()
  }
}
onMounted(async () => {
  if (props.embedded) return
  await nextTick()
  dialog.value?.focus({ preventScroll: true })
})
onBeforeUnmount(() => {
  if (!props.embedded && trigger?.isConnected) trigger.focus({ preventScroll: true })
})
</script>
<template>
  <Teleport to="body" :disabled="embedded"
    ><div
      class="settings-dialog-mask"
      :class="{ embedded }"
      :data-wao-theme="theme"
      :style="theme ? { colorScheme: theme } : undefined"
      @click.self="!embedded && outside()"
    >
      <section
        ref="dialog"
        class="settings-dialog-shell"
        :style="{ '--settings-dialog-width': width, '--settings-dialog-height': height }"
        :role="embedded ? 'region' : 'dialog'"
        :aria-modal="embedded ? undefined : true"
        :aria-label="title"
        tabindex="-1"
        @keydown="trap"
      >
        <header class="dialog-heading">
          <div class="title-icon"><i class="ri-sparkling-2-line" aria-hidden="true"></i></div>
          <div>
            <h2>{{ title }}</h2>
            <p v-if="description">{{ description }}</p>
          </div>
          <button
            v-if="!embedded"
            class="dialog-close"
            type="button"
            :aria-label="`关闭${title}`"
            @click="emit('close')"
          >
            <i class="ri-close-line" aria-hidden="true"></i>
          </button>
        </header>
        <div class="dialog-content"><slot /></div>
        <footer v-if="$slots.footer" class="dialog-footer"><slot name="footer" /></footer>
      </section></div
  ></Teleport>
</template>
<style scoped>
.settings-dialog-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  box-sizing: border-box;
  background: var(--wao-mask-bg, #12223c70);
}
.settings-dialog-shell {
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  width: min(var(--settings-dialog-width), 100%);
  height: var(--settings-dialog-height);
  max-height: calc(100dvh - 48px);
  min-height: 0;
  overflow: hidden;
  border: 1px solid var(--wao-divider);
  border-radius: var(--wao-radius-modal);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  box-shadow: var(--wao-shadow-dialog);
  outline: none;
  font: 14px/1.6 var(--wao-font, 'Segoe UI', 'Microsoft YaHei', sans-serif);
}
.dialog-heading {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 22px 26px;
  border-bottom: 1px solid var(--wao-divider);
}
h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 0.3px;
}
p {
  margin: 3px 0 0;
  color: var(--wao-text-3);
  font-size: 13px;
  line-height: 1.6;
}
.title-icon {
  flex-shrink: 0;
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  border-radius: 12px;
  font-size: 22px;
}
.dialog-close {
  margin-left: auto;
  flex-shrink: 0;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background: var(--wao-bg-sub);
  color: var(--wao-text-weak);
  cursor: pointer;
  font-size: 20px;
}
.dialog-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: auto;
}
.dialog-footer {
  flex-shrink: 0;
  border-top: 1px solid var(--wao-divider);
  padding: 15px 26px;
}
button:focus-visible {
  outline: 2px solid var(--wao-primary);
  outline-offset: 2px;
}
@media (max-width: 650px) {
  .settings-dialog-mask {
    padding: 0;
  }
  .settings-dialog-shell {
    width: 100%;
    height: 100dvh;
    max-height: 100dvh;
    border-radius: 0;
    border: 0;
  }
  .dialog-heading {
    padding: 18px 16px;
  }
  h2 {
    font-size: 20px;
  }
  .title-icon {
    width: 42px;
    height: 42px;
  }
  .dialog-footer {
    padding: 12px 15px;
  }
}
</style>

<style scoped>
.settings-dialog-mask.embedded {
  position: static;
  display: block;
  padding: 0;
  background: none;
  z-index: auto;
  width: 100%;
  min-width: 0;
}
.embedded .settings-dialog-shell {
  width: 100%;
  height: 640px;
  max-height: none;
  border: 0;
  border-radius: 0;
  box-shadow: none;
}
.embedded .dialog-heading {
  display: none;
}
@media (max-width: 900px) {
  .embedded .settings-dialog-shell {
    height: auto;
    min-height: 640px;
    overflow: visible;
  }
}
</style>
