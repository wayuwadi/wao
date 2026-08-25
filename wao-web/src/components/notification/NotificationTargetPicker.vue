<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { FeishuChat, FeishuWebhookEndpoint } from '@/api/system'

const props = defineProps<{
  enabled: boolean
  channel: 'WEBHOOK' | 'CHAT'
  webhookId: string
  webhookName: string
  chatId: string
  chatName: string
  webhooks: FeishuWebhookEndpoint[]
  chats: FeishuChat[]
  loading: boolean
  saving: boolean
  error: string
}>()

const emit = defineEmits<{
  open: []
  select: [target: { channel: 'WEBHOOK' | 'CHAT'; id: string; name: string } | null]
}>()

const root = ref<HTMLElement | null>(null)
const open = ref(false)

const selectedName = computed(() => {
  if (!props.enabled) return '不发群'
  if (props.channel === 'WEBHOOK') return props.webhookName || '已选 Webhook'
  return props.chatName || '已选群聊'
})

function toggle() {
  if (props.saving) return
  open.value = !open.value
  if (open.value) emit('open')
}

function select(target: { channel: 'WEBHOOK' | 'CHAT'; id: string; name: string } | null) {
  emit('select', target)
  open.value = false
}

function closeOutside(event: PointerEvent) {
  if (open.value && root.value && !root.value.contains(event.target as Node)) open.value = false
}

onMounted(() => document.addEventListener('pointerdown', closeOutside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', closeOutside))
</script>

<template>
  <div ref="root" class="notification-target-picker">
    <button
      class="notification-target-trigger"
      type="button"
      :disabled="saving"
      :aria-expanded="open"
      :aria-label="`通知群：${selectedName}`"
      aria-haspopup="menu"
      @click="toggle"
    >
      <i :class="saving ? 'ri-loader-4-line spin' : 'ri-notification-3-line'"></i>
      <span>{{ saving ? '保存中' : selectedName }}</span>
      <i class="ri-arrow-up-s-line"></i>
    </button>

    <div v-if="open" class="notification-target-menu" role="menu">
      <header>
        <strong>保存后通知</strong>
        <small>选择会立即保存到你的账号</small>
      </header>
      <button
        class="notification-target-option"
        :class="{ selected: !enabled }"
        type="button"
        role="menuitemradio"
        :aria-checked="!enabled"
        @click="select(null)"
      >
        <i class="ri-notification-off-line"></i>
        <span><b>不发送群通知</b><small>只保存今日重点</small></span>
        <i v-if="!enabled" class="ri-check-line"></i>
      </button>

      <div v-if="loading" class="notification-target-state">
        <i class="ri-loader-4-line spin"></i>正在读取真实通知群…
      </div>
      <template v-else>
        <section v-if="webhooks.some((item) => item.enabled)">
          <small>系统 Webhook</small>
          <button
            v-for="endpoint in webhooks.filter((item) => item.enabled)"
            :key="endpoint.id"
            class="notification-target-option"
            :class="{ selected: enabled && channel === 'WEBHOOK' && webhookId === endpoint.id }"
            type="button"
            role="menuitemradio"
            :aria-checked="enabled && channel === 'WEBHOOK' && webhookId === endpoint.id"
            @click="select({ channel: 'WEBHOOK', id: endpoint.id, name: endpoint.name })"
          >
            <i class="ri-robot-2-line"></i>
            <span
              ><b>{{ endpoint.name }}</b
              ><small>{{
                endpoint.systemDefault ? '系统默认项，可在此明确选择' : '已启用'
              }}</small></span
            >
            <i
              v-if="enabled && channel === 'WEBHOOK' && webhookId === endpoint.id"
              class="ri-check-line"
            ></i>
          </button>
        </section>
        <section v-if="chats.length">
          <small>飞书应用群聊</small>
          <button
            v-for="chat in chats"
            :key="chat.chatId"
            class="notification-target-option"
            :class="{ selected: enabled && channel === 'CHAT' && chatId === chat.chatId }"
            type="button"
            role="menuitemradio"
            :aria-checked="enabled && channel === 'CHAT' && chatId === chat.chatId"
            @click="select({ channel: 'CHAT', id: chat.chatId, name: chat.name })"
          >
            <i class="ri-group-line"></i>
            <span
              ><b>{{ chat.name }}</b
              ><small>机器人所在群</small></span
            >
            <i
              v-if="enabled && channel === 'CHAT' && chatId === chat.chatId"
              class="ri-check-line"
            ></i>
          </button>
        </section>
        <div
          v-if="!webhooks.some((item) => item.enabled) && !chats.length"
          class="notification-target-state"
        >
          暂无可用通知群，请先到系统设置配置 Webhook 或飞书应用。
        </div>
      </template>
      <div v-if="error" class="notification-target-error" role="alert">{{ error }}</div>
    </div>
  </div>
</template>

<style scoped>
.notification-target-picker {
  position: relative;
}
.notification-target-trigger {
  max-width: 180px;
  height: 31px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
  padding: 0 9px;
  display: flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
  font-size: 10px;
}
.notification-target-trigger span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notification-target-trigger:disabled {
  opacity: 0.55;
  cursor: wait;
}
.notification-target-menu {
  position: absolute;
  z-index: 20;
  left: 0;
  bottom: calc(100% + 7px);
  width: min(310px, calc(100vw - 52px));
  max-height: min(420px, 60vh);
  overflow-y: auto;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  background: var(--wao-bg-card);
  box-shadow: 0 18px 46px rgba(14, 28, 24, 0.18);
  padding: 8px;
}
.notification-target-menu header {
  padding: 4px 5px 8px;
  display: grid;
  gap: 2px;
}
.notification-target-menu header strong {
  color: var(--wao-text);
  font-size: 12px;
}
.notification-target-menu header small,
.notification-target-menu section > small {
  color: var(--wao-text-faint);
  font-size: 9px;
}
.notification-target-menu section {
  margin-top: 8px;
}
.notification-target-menu section > small {
  display: block;
  padding: 0 5px 4px;
}
.notification-target-option {
  width: 100%;
  min-height: 46px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--wao-text-2);
  padding: 7px 8px;
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr) 18px;
  align-items: center;
  gap: 7px;
  text-align: left;
  cursor: pointer;
}
.notification-target-option:hover,
.notification-target-option.selected {
  background: color-mix(in srgb, var(--wao-primary) 9%, var(--wao-bg-card));
}
.notification-target-option > i {
  color: var(--wao-primary);
  font-size: 14px;
}
.notification-target-option span {
  min-width: 0;
  display: grid;
  gap: 2px;
}
.notification-target-option b,
.notification-target-option small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.notification-target-option b {
  color: var(--wao-text);
  font-size: 10.5px;
}
.notification-target-option small {
  color: var(--wao-text-faint);
  font-size: 9px;
}
.notification-target-state,
.notification-target-error {
  margin: 7px 4px 2px;
  border-radius: 8px;
  padding: 8px;
  color: var(--wao-text-weak);
  background: var(--wao-bg-sub);
  font-size: 9.5px;
  line-height: 1.45;
}
.notification-target-error {
  color: var(--wao-danger-strong);
  background: color-mix(in srgb, var(--wao-danger) 8%, var(--wao-bg-card));
}
.spin {
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 420px) {
  .notification-target-trigger {
    max-width: 132px;
  }
}
</style>
