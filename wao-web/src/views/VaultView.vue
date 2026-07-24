<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDataStore } from '@/stores/data'
import { copyText } from '@/utils/clipboard'
import { toast } from '@/composables/toast'
import DemoBanner from '@/components/ui/DemoBanner.vue'

const data = useDataStore()
const group = ref('全部')
const selName = ref<string | null>(null)

interface FlatItem {
  group: string
  name: string
  icon: string
  user: string
  site: string
  note: string
  updated: string
}

const items = computed<FlatItem[]>(() =>
  data.vault.flatMap((g) => g.items.map((it) => ({ ...it, group: g.group }))),
)
const filtered = computed(() => (group.value === '全部' ? items.value : items.value.filter((i) => i.group === group.value)))
const sel = computed(() => filtered.value.find((i) => i.name === selName.value) ?? filtered.value[0] ?? null)

const groups = computed(() =>
  ['全部', ...data.vault.map((g) => g.group)].map((g) => ({
    label: g,
    count: g === '全部' ? items.value.length : items.value.filter((i) => i.group === g).length,
    on: group.value === g,
  })),
)

function pickGroup(g: string) {
  group.value = g
  selName.value = null
}
function copyUser() {
  if (sel.value) copyText(sel.value.user, '已复制用户名（演示）')
}
function passLocked() {
  toast('演示环境不存储真实密码，无法查看 / 复制', 'ri-lock-line')
}
function comingSoon() {
  toast('演示：添加条目需先完成端到端加密设计')
}
</script>

<template>
  <div class="vault">
    <DemoBanner
      icon="ri-key-2-line"
      title="密码库 · 功能演示"
      text="当前为示例凭据，仅用于演示分组与检索形态，不含任何真实密码。端到端加密存储、主密码解锁与自动填充将在后续迭代交付。"
      storage-key="vault"
    />
    <div class="vtop">
      <div class="vtitle">密码库</div>
      <span class="vtip">分组管理 · 全部为虚构示例</span>
      <div class="spacer"></div>
      <button class="btn-ghost" @click="comingSoon"><i class="ri-add-line"></i>添加条目</button>
    </div>

    <div class="banner">
      <i class="ri-shield-check-line"></i>
      <span>本页仅为脱敏示意，<b>所有内容均为虚构，请勿输入或保存任何真实密码</b>。正式开发时将采用端到端加密、独立主密码与安全审计，与其他模块数据完全隔离。</span>
    </div>

    <div class="cols">
      <div class="group-col">
        <div class="col-label">分组</div>
        <button v-for="g in groups" :key="g.label" class="group-btn" :class="{ on: g.on }" @click="pickGroup(g.label)">
          <span class="gb-label">{{ g.label }}</span><span class="gb-count wao-tabular">{{ g.count }}</span>
        </button>
      </div>

      <div class="item-col">
        <button v-for="it in filtered" :key="it.name" class="item-btn" :class="{ on: sel?.name === it.name }" @click="selName = it.name">
          <div class="item-icon"><i :class="it.icon"></i></div>
          <div class="item-main">
            <div class="item-name">{{ it.name }}</div>
            <div class="item-user">{{ it.user }}</div>
          </div>
        </button>
      </div>

      <div class="detail-col">
        <template v-if="sel">
          <div class="d-head">
            <div class="d-icon"><i :class="sel.icon"></i></div>
            <div>
              <div class="d-name">{{ sel.name }}</div>
              <div class="d-sub">{{ sel.group }} · 更新于 {{ sel.updated }}</div>
            </div>
          </div>
          <div class="fields">
            <div class="field-row">
              <span class="f-key">用户名</span>
              <span class="f-val wao-mono">{{ sel.user }}</span>
              <button class="f-btn" @click="copyUser">复制</button>
            </div>
            <div class="field-row">
              <span class="f-key">密码</span>
              <span class="f-pass">••••••••••</span>
              <button class="f-icon" title="查看" @click="passLocked"><i class="ri-eye-off-line"></i></button>
              <button class="f-btn" @click="passLocked">复制</button>
            </div>
            <div class="field-row last">
              <span class="f-key">网址</span>
              <span class="f-site">{{ sel.site }}</span>
            </div>
          </div>
          <div v-if="sel.note" class="note"><i class="ri-sticky-note-line"></i>{{ sel.note }}</div>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.vault {
  padding: 16px 24px 40px;
  max-width: var(--wao-content-max);
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.spacer {
  flex: 1;
}
.vtop {
  display: flex;
  align-items: center;
  gap: 10px;
}
.vtitle {
  font-size: 20px;
  font-weight: 700;
}
.vtip {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.btn-ghost {
  height: 32px;
  padding: 0 13px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-ghost:hover {
  background: #f4f5f8;
}
.btn-ghost i {
  font-size: 14px;
}
.banner {
  margin-top: 12px;
  background: #fffdf7;
  border: 1px solid #f0e3c4;
  border-radius: 12px;
  padding: 11px 16px;
  display: flex;
  gap: 10px;
  font-size: 12px;
  color: #8a5b10;
  line-height: 1.7;
}
.banner i {
  font-size: 15px;
  flex-shrink: 0;
  margin-top: 2px;
}

.cols {
  margin-top: 12px;
  display: flex;
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  overflow: hidden;
  min-height: 420px;
}
.group-col {
  width: 158px;
  flex-shrink: 0;
  border-right: 1px solid var(--wao-divider);
  background: var(--wao-bg-sub);
  padding: 10px 8px;
}
.col-label {
  padding: 2px 8px 6px;
  font-size: 11px;
  color: var(--wao-text-weak);
  letter-spacing: 1.5px;
}
.group-btn {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 7px;
  border: none;
  border-radius: 7px;
  padding: 7px 9px;
  cursor: pointer;
  background: transparent;
  color: var(--wao-text-2);
  font-size: 12.5px;
}
.group-btn:hover {
  background: #f0f1f4;
}
.group-btn.on {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  font-weight: 600;
}
.gb-label {
  flex: 1;
  text-align: left;
}
.gb-count {
  font-size: 11px;
  color: var(--wao-text-faint);
}

.item-col {
  width: 252px;
  flex-shrink: 0;
  border-right: 1px solid var(--wao-divider);
  overflow-y: auto;
  padding: 6px;
}
.item-btn {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  border: none;
  border-radius: 9px;
  padding: 9px 10px;
  cursor: pointer;
  background: transparent;
  text-align: left;
}
.item-btn:hover {
  background: #f4f5f8;
}
.item-btn.on {
  background: var(--wao-primary-soft);
}
.item-icon {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: #f0f1f4;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.item-icon i {
  font-size: 14px;
  color: var(--wao-text-3);
}
.item-main {
  flex: 1;
  min-width: 0;
}
.item-name {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--wao-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.item-user {
  margin-top: 1px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.detail-col {
  flex: 1;
  min-width: 0;
  padding: 20px 24px;
}
.d-head {
  display: flex;
  align-items: center;
  gap: 12px;
}
.d-icon {
  width: 42px;
  height: 42px;
  border-radius: 11px;
  background: #f0f1f4;
  display: flex;
  align-items: center;
  justify-content: center;
}
.d-icon i {
  font-size: 19px;
  color: var(--wao-text-3);
}
.d-name {
  font-size: 16px;
  font-weight: 700;
}
.d-sub {
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
}
.fields {
  margin-top: 16px;
  border: 1px solid var(--wao-divider);
  border-radius: 10px;
  overflow: hidden;
}
.field-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--wao-divider-2);
}
.field-row.last {
  border-bottom: none;
}
.f-key {
  width: 56px;
  font-size: 12px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.f-val {
  flex: 1;
  font-size: 13px;
  color: var(--wao-text);
}
.f-pass {
  flex: 1;
  font-size: 14px;
  color: var(--wao-text-faint);
  letter-spacing: 2px;
}
.f-site {
  flex: 1;
  font-size: 13px;
  color: var(--wao-primary-text);
}
.f-btn {
  height: 26px;
  padding: 0 10px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 7px;
  font-size: 11.5px;
  color: var(--wao-text-2);
  cursor: pointer;
}
.f-btn:hover {
  background: #f4f5f8;
}
.f-icon {
  width: 26px;
  height: 26px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 7px;
  cursor: pointer;
  color: var(--wao-text-weak);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.f-icon:hover {
  background: #f4f5f8;
}
.f-icon i {
  font-size: 13px;
}
.note {
  margin-top: 12px;
  font-size: 12px;
  color: var(--wao-text-weak);
  line-height: 1.7;
}
.note i {
  font-size: 13px;
  margin-right: 5px;
}
</style>
