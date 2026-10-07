<script setup lang="ts">
import WaoButton from '@/components/ui/WaoButton.vue'
import WaoField from '@/components/ui/WaoField.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session'
import {
  fetchDigitalLibrary,
  saveDigitalProfile,
  type DigitalGender,
  type DigitalLibrary,
} from '@/api/digitalMembers'
const session = useSessionStore(),
  loading = ref(true),
  saving = ref(false),
  error = ref(''),
  status = ref('')
const profile = ref<DigitalLibrary['profile'] | null>(null),
  draft = ref<{ gender: DigitalGender; expectedRevision: number } | null>(null)
const conflict = ref(false)
let epoch = 0,
  restoring = false
const key = () => `wao-digital-profile-draft-v1:${session.userId}`
const dirty = computed(() => !!draft.value && draft.value.gender !== profile.value?.gender)
function remember() {
  if (!draft.value || restoring || !session.userId) return
  try {
    localStorage.setItem(key(), JSON.stringify(draft.value))
  } catch {
    status.value = '本机草稿无法持久保存，请保存后再离开'
  }
}
watch(draft, remember, { deep: true, flush: 'sync' })
async function load() {
  const current = ++epoch
  loading.value = true
  error.value = ''
  try {
    const value = (await fetchDigitalLibrary()).profile
    if (current !== epoch) return
    profile.value = value
    if (!draft.value) {
      restoring = true
      try {
        const stored = JSON.parse(localStorage.getItem(key()) || 'null')
        if (
          stored &&
          ['male', 'female', 'unknown'].includes(stored.gender) &&
          typeof stored.expectedRevision === 'number'
        )
          draft.value = stored
      } catch {
        /* 账号值保留 */
      }
      draft.value ??= { gender: value.gender, expectedRevision: value.revision }
      restoring = false
    }
    conflict.value = draft.value.expectedRevision !== value.revision
  } catch (cause) {
    if (current === epoch) error.value = cause instanceof Error ? cause.message : '个人事实读取失败'
  } finally {
    if (current === epoch) loading.value = false
  }
}
function resolve(keep: boolean) {
  if (!profile.value || !draft.value) return
  if (!keep) draft.value.gender = profile.value.gender
  draft.value.expectedRevision = profile.value.revision
  conflict.value = false
  error.value = ''
  remember()
}
async function save() {
  if (!draft.value || saving.value || conflict.value) return
  const current = epoch,
    member = session.userId
  saving.value = true
  error.value = ''
  try {
    const value = await saveDigitalProfile(draft.value.gender, draft.value.expectedRevision)
    if (current !== epoch) return
    profile.value = value
    draft.value = { gender: value.gender, expectedRevision: value.revision }
    try {
      localStorage.removeItem(key())
    } catch {
      /* 已保存 */
    }
    status.value = '个人事实已保存'
  } catch (cause) {
    if (current !== epoch) return
    error.value = cause instanceof Error ? cause.message : '保存失败，请重试'
    if ((cause as { status?: number })?.status === 409) await load()
  } finally {
    if (member === session.userId) saving.value = false
  }
}
watch(
  () => session.userId,
  () => {
    epoch++
    restoring = true
    draft.value = null
    profile.value = null
    restoring = false
    saving.value = false
    status.value = ''
    void load()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  epoch++
})
</script>
<template>
  <section class="profile-facts">
    <h3>我的基本事实</h3>
    <p>用于理解小记中的“我”；家人与宠物在数字成员图库独立填写。</p>
    <p v-if="loading" role="status">正在读取个人事实…</p>
    <div v-else-if="profile && draft" class="facts-form">
      <WaoField v-slot="field" label="性别"
        ><select :id="field.id" v-model="draft.gender" :disabled="saving">
          <option value="unknown">未知 / 暂不填写</option>
          <option value="male">男性</option>
          <option value="female">女性</option>
        </select></WaoField
      >
      <div v-if="conflict" class="error" role="alert">
        账号事实已更新：{{
          { male: '男性', female: '女性', unknown: '未知' }[profile.gender]
        }}；本机草稿保留。
        <div>
          <WaoButton @click="resolve(true)">保留草稿，基于最新版本</WaoButton
          ><WaoButton @click="resolve(false)">使用账号事实</WaoButton>
        </div>
      </div>
      <WaoButton
        class="primary"
        variant="primary"
        :disabled="saving || !dirty || conflict"
        @click="save"
      >
        {{ saving ? '保存中…' : '保存个人事实' }}</WaoButton
      ><span role="status">{{
        dirty ? '草稿已保留，保存后生效' : status || '已读取账号事实'
      }}</span>
    </div>
    <p v-if="error" class="error" role="alert">
      {{ error }} <WaoButton :disabled="loading || saving" @click="load">重新读取</WaoButton
      ><WaoButton v-if="draft && dirty && !conflict" :disabled="saving" @click="save"
        >重试保存</WaoButton
      >
    </p>
    <RouterLink to="/digital-members">管理数字成员图库与小记默认形象</RouterLink>
  </section>
</template>
<style scoped>
.profile-facts {
  padding-top: 17px;
  margin-top: 17px;
  border-top: 1px solid var(--wao-divider);
  font-size: 13px;
}
.profile-facts h3 {
  font-size: 15px;
  margin: 0;
}
.profile-facts p,
.profile-facts span {
  color: var(--wao-text-3);
  font-size: 12px;
  line-height: 1.6;
}
.facts-form {
  display: flex;
  align-items: end;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.facts-form label {
  display: grid;
  gap: 6px;
}
.facts-form select {
  background: var(--wao-bg-sub);
  color: var(--wao-text);
  border: 1px solid var(--wao-divider);
  padding: 8px;
  border-radius: var(--wao-radius-control);
  min-height: 40px;
}
.profile-facts button {
  padding: 8px 11px;
  border: 1px solid var(--wao-divider);
  background: var(--wao-bg-card);
  color: var(--wao-text);
  border-radius: var(--wao-radius-control);
  cursor: pointer;
  min-height: 40px;
}
.profile-facts .primary {
  background: var(--wao-primary);
  color: var(--wao-on-primary);
}
.profile-facts .error {
  color: var(--wao-danger-strong);
}
.profile-facts a {
  color: var(--wao-primary-text);
}
button:disabled {
  opacity: 0.5;
}
.error > div {
  display: flex;
  gap: 7px;
  flex-wrap: wrap;
}
@media (max-width: 650px) {
  .facts-form select {
    font-size: 16px;
  }
  .profile-facts :deep(.wao-button) {
    min-height: 44px;
  }
}
</style>
