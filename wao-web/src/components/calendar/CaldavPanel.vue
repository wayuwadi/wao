<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { toast } from '@/composables/toast'
import {
  apiAddCaldavAccount, apiDeleteCaldavAccount, apiSyncCaldavAccount, apiToggleCaldavCalendar,
  fetchCaldavAccounts, type CaldavAccount, type CaldavCalendar,
} from '@/api/caldav'

/**
 * CalDAV 账户配置面板。原先住在系统设置里，但 CalDAV 账户是个人维度的数据，
 * 配置入口应该长在日历页——在哪看日历，就在哪接日历。
 */
const emit = defineEmits<{ (e: 'changed'): void }>()

// null = 还没加载过；加载失败落成 []，界面上有重试入口。
const accounts = ref<CaldavAccount[] | null>(null)
const loading = ref(false)
const busy = ref<string | null>(null)
const showForm = ref(false)
const form = reactive({ name: '', serverUrl: '', username: '', password: '' })

async function load() {
  loading.value = true
  try {
    accounts.value = await fetchCaldavAccounts()
  } catch (error) {
    accounts.value = accounts.value ?? []
    toast(error instanceof Error ? error.message : '读取 CalDAV 账户失败')
  } finally {
    loading.value = false
  }
}
onMounted(load)

async function addAccount() {
  if (!form.serverUrl.trim() || !form.username.trim() || !form.password) {
    toast('请填写服务器地址、账号和密码')
    return
  }
  busy.value = 'add'
  try {
    const account = await apiAddCaldavAccount({
      name: form.name.trim() || undefined,
      serverUrl: form.serverUrl.trim(),
      username: form.username.trim(),
      password: form.password,
    })
    accounts.value = [...(accounts.value ?? []), account]
    Object.assign(form, { name: '', serverUrl: '', username: '', password: '' })
    showForm.value = false
    toast(`已连接，发现 ${account.calendars.length} 个日历并完成首次同步`, 'ri-calendar-check-line')
    emit('changed')
  } catch (error) {
    toast(error instanceof Error ? error.message : '连接失败，请检查地址与凭据')
  } finally {
    busy.value = null
  }
}

async function removeAccount(account: CaldavAccount) {
  if (!window.confirm(`删除「${account.name}」？其订阅日历与事件副本会一并移除，不影响服务器上的数据。`)) return
  busy.value = account.id
  try {
    await apiDeleteCaldavAccount(account.id)
    accounts.value = (accounts.value ?? []).filter((a) => a.id !== account.id)
    toast(`已删除「${account.name}」`, 'ri-delete-bin-6-line')
    emit('changed')
  } catch (error) {
    toast(error instanceof Error ? error.message : '删除失败')
  } finally {
    busy.value = null
  }
}

async function syncAccount(account: CaldavAccount) {
  busy.value = account.id
  try {
    const fresh = await apiSyncCaldavAccount(account.id)
    accounts.value = (accounts.value ?? []).map((a) => (a.id === fresh.id ? fresh : a))
    toast(fresh.status === 'ok' ? '同步完成，日历页已是最新' : `同步失败：${fresh.syncError ?? '未知原因'}`,
      fresh.status === 'ok' ? 'ri-refresh-line' : 'ri-error-warning-line')
    emit('changed')
  } catch (error) {
    toast(error instanceof Error ? error.message : '同步失败')
  } finally {
    busy.value = null
  }
}

async function toggleCalendar(cal: CaldavCalendar) {
  busy.value = cal.id
  try {
    const fresh = await apiToggleCaldavCalendar(cal.id, !cal.enabled)
    cal.enabled = fresh.enabled
    toast(fresh.enabled ? `已订阅「${cal.name}」` : `已停用「${cal.name}」`, 'ri-calendar-line')
    emit('changed')
  } catch (error) {
    toast(error instanceof Error ? error.message : '操作失败')
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <div v-if="loading && accounts === null" class="card">
    <div class="hint">正在读取 CalDAV 账户…</div>
  </div>

  <template v-else>
    <div v-if="(accounts ?? []).length === 0" class="card">
      <div class="card-title">还没有连接日历账户</div>
      <div class="hint">连接 iCloud、飞书、Nextcloud 等支持 CalDAV 的日历后，事件会与系统日历合并显示，每 15 分钟自动同步一次。账户按登录人隔离，只影响你自己的日历。</div>
    </div>

    <div v-for="account in accounts ?? []" :key="account.id" class="card">
      <div class="card-row wrap">
        <div class="card-title">{{ account.name }}</div>
        <span class="connected" :class="{ err: account.status !== 'ok' }">
          <span class="c-dot"></span>{{ account.status === 'ok' ? '已连接' : '同步异常' }}
        </span>
        <div class="spacer"></div>
        <button class="btn-ghost sm" :disabled="busy === account.id" @click="syncAccount(account)">
          <i class="ri-refresh-line"></i>{{ busy === account.id ? '同步中…' : '立即同步' }}
        </button>
        <button class="btn-ghost sm danger" :disabled="busy === account.id" @click="removeAccount(account)">
          <i class="ri-delete-bin-line"></i>删除
        </button>
      </div>
      <div class="kv-list">
        <div class="kv"><span class="kv-k">服务器</span><span class="kv-v wao-mono">{{ account.serverUrl }}</span></div>
        <div class="kv"><span class="kv-k">账号</span><span class="kv-v">{{ account.username }}</span></div>
        <div class="kv"><span class="kv-k">同步</span><span class="kv-v">每 15 分钟 · {{ account.lastSyncAt ? `上次同步 ${account.lastSyncAt}` : '尚未同步' }}</span></div>
        <div v-if="account.syncError" class="kv"><span class="kv-k">错误</span><span class="kv-v err-text">{{ account.syncError }}</span></div>
      </div>
      <div class="sub-block">
        <div class="sub-label">订阅的日历（与系统日历合并显示）</div>
        <div v-if="account.calendars.length === 0" class="hint">这个账户下没有发现日历</div>
        <div v-for="c in account.calendars" :key="c.id" class="cal-row">
          <span class="cal-dot" :style="{ background: c.color }"></span>
          <span class="cal-name">{{ c.name }}</span>
          <button class="toggle" :class="{ on: c.enabled }" :disabled="busy === c.id" title="启用 / 停用" @click="toggleCalendar(c)"><span class="knob"></span></button>
        </div>
      </div>
    </div>

    <div class="card">
      <div class="card-row">
        <div class="card-title">添加账户</div>
        <div class="spacer"></div>
        <button v-if="!showForm" class="btn-primary sm" @click="showForm = true"><i class="ri-add-line"></i>连接 CalDAV</button>
      </div>
      <form v-if="showForm" class="site-form" @submit.prevent="addAccount">
        <label><span>显示名称</span><input v-model.trim="form.name" maxlength="64" placeholder="例如 家庭日历（可留空）" /></label>
        <label><span>服务器地址</span><input v-model.trim="form.serverUrl" required placeholder="https://caldav.example.com" /></label>
        <label><span>账号</span><input v-model.trim="form.username" required placeholder="登录邮箱或用户名" /></label>
        <label><span>密码</span><input v-model="form.password" type="password" required placeholder="账户密码或应用专用密码" /></label>
        <div class="hint form-hint">密码加密存储，仅用于向你的日历服务器同步；iCloud 等需使用「应用专用密码」。</div>
        <div class="site-actions">
          <button class="btn-primary" :disabled="busy === 'add'">{{ busy === 'add' ? '连接中…' : '连接并同步' }}</button>
          <button type="button" class="btn-ghost" :disabled="busy === 'add'" @click="showForm = false">取消</button>
        </div>
      </form>
      <div v-else class="hint">支持 iCloud、飞书、Nextcloud、Fastmail 等标准 CalDAV 服务。</div>
    </div>
  </template>
</template>

<style scoped>
.spacer {
  flex: 1;
}
.card {
  margin-top: 14px;
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 12px;
  padding: 14px 18px;
}
.card:first-child {
  margin-top: 0;
}
.card-row {
  display: flex;
  align-items: center;
}
.card-row.wrap {
  flex-wrap: wrap;
  gap: 10px;
}
.card-title {
  font-size: 14px;
  font-weight: 600;
}
.btn-primary {
  height: 34px;
  padding: 0 14px;
  background: var(--wao-primary);
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn-primary:hover {
  background: var(--wao-primary-hover);
}
.btn-primary i {
  font-size: 13px;
}
.btn-primary.sm {
  height: 30px;
  padding: 0 12px;
}
.btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.btn-ghost {
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--wao-line-strong);
  background: #fff;
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--wao-text-2);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.btn-ghost:hover {
  background: #f4f5f8;
}
.btn-ghost.sm {
  height: 29px;
  padding: 0 11px;
  font-size: 12px;
}
.btn-ghost i {
  font-size: 13px;
}
.btn-ghost:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.btn-ghost.danger {
  color: var(--wao-danger);
}
.btn-ghost.danger:hover {
  border-color: var(--wao-danger);
}
.hint {
  margin-top: 10px;
  font-size: 12px;
  color: var(--wao-text-weak);
  line-height: 1.8;
}
.connected {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--wao-success-strong);
}
.c-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--wao-success);
}
.connected.err {
  color: var(--wao-danger);
}
.connected.err .c-dot {
  background: var(--wao-danger);
}
.err-text {
  color: var(--wao-danger);
}
.kv-list {
  margin-top: 10px;
  display: flex;
  flex-direction: column;
  gap: 9px;
  font-size: 12.5px;
}
.kv {
  display: flex;
  gap: 10px;
  align-items: center;
}
.kv-k {
  width: 64px;
  color: var(--wao-text-weak);
  flex-shrink: 0;
}
.kv-v {
  color: var(--wao-text-2);
  word-break: break-all;
}
.sub-block {
  margin-top: 12px;
  border-top: 1px solid var(--wao-divider-2);
  padding-top: 10px;
}
.sub-label {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.cal-row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 7px 0;
}
.cal-dot {
  width: 9px;
  height: 9px;
  border-radius: 3px;
}
.cal-name {
  flex: 1;
  font-size: 13px;
  color: var(--wao-text);
}
.toggle {
  width: 34px;
  height: 18px;
  border: none;
  border-radius: 999px;
  background: #d9dce5;
  cursor: pointer;
  position: relative;
  padding: 0;
}
.toggle.on {
  background: var(--wao-primary);
}
.toggle:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  transition: left 0.15s ease;
}
.toggle.on .knob {
  left: 16px;
}
.site-form {
  margin-top: 16px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 13px 16px;
}
.site-form label {
  display: grid;
  gap: 6px;
  color: var(--wao-text-3);
  font-size: 12px;
}
.site-form input {
  height: 36px;
  box-sizing: border-box;
  padding: 0 11px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  outline: none;
  color: var(--wao-text);
}
.site-form input:focus {
  border-color: var(--wao-primary);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.form-hint {
  grid-column: 1 / -1;
  margin-top: 0;
}
.site-actions {
  grid-column: 1 / -1;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
</style>
