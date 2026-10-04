<script setup lang="ts">
import SettingsDialogShell from '@/components/ui/SettingsDialogShell.vue'
import { onMounted, reactive, ref } from 'vue'
import { fetchFileSettings, saveFileSettings, type FileSettings } from '@/api/system'
import { toast } from '@/composables/toast'

const emit = defineEmits<{
  close: []
  saved: [settings: FileSettings]
}>()

const form = reactive<FileSettings>({
  fileMaxMb: 200,
  imageMaxMb: 10,
  downloadCaptchaEnabled: true,
})
const loading = ref(true)
const loaded = ref(false)
const saving = ref(false)
const error = ref('')
const original = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    Object.assign(form, await fetchFileSettings())
    original.value = JSON.stringify(form)
    loaded.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '文件设置读取失败，请重试'
  } finally {
    loading.value = false
  }
}

async function save() {
  if (saving.value || !loaded.value) return
  if (
    !Number.isInteger(form.fileMaxMb) ||
    form.fileMaxMb < 1 ||
    form.fileMaxMb > 200 ||
    !Number.isInteger(form.imageMaxMb) ||
    form.imageMaxMb < 1 ||
    form.imageMaxMb > 10
  ) {
    error.value = '请输入有效上传上限：普通文件 1–200MB，图片 1–10MB'
    return
  }
  saving.value = true
  error.value = ''
  try {
    const result = await saveFileSettings({ ...form })
    Object.assign(form, result)
    emit('saved', result)
    toast('文件与图床设置已保存', 'ri-check-line')
    emit('close')
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '设置保存失败，当前输入已保留'
  } finally {
    saving.value = false
  }
}

async function close() {
  if (saving.value) return
  if (loaded.value && JSON.stringify(form) !== original.value) await save()
  else emit('close')
}
onMounted(load)
</script>

<template>
  <SettingsDialogShell
    title="文件与图床设置"
    description="管理上传上限与公开下载验证"
    width="760px"
    height="auto"
    @close="close"
  >
    <form id="file-settings-form" class="file-settings-body" @submit.prevent="save">
      <div v-if="loading" class="state"><i class="ri-loader-4-line spin"></i>正在读取真实设置…</div>
      <div v-else-if="!loaded" class="load-error" role="alert">
        <i class="ri-error-warning-line"></i><span>{{ error }}</span
        ><button type="button" @click="load">重新读取</button>
      </div>
      <template v-else>
        <div class="fields" :inert="saving || undefined">
          <label>
            <span>普通文件上传上限</span>
            <div>
              <input v-model.number="form.fileMaxMb" type="number" min="1" max="200" required /><b
                >MB</b
              >
            </div>
            <small>1–200MB；网关与服务端请求体基线为 1GB。</small>
          </label>
          <label>
            <span>图床单图上传上限</span>
            <div>
              <input v-model.number="form.imageMaxMb" type="number" min="1" max="10" required /><b
                >MB</b
              >
            </div>
            <small>1–10MB；服务端抓取的飞书图片仍受 10MB 安全上限保护。</small>
          </label>
          <label class="switch-row">
            <input v-model="form.downloadCaptchaEnabled" type="checkbox" />
            <span
              ><b>公开下载启用算术验证码</b
              ><small
                >关闭后仍需点击下载，并继续使用两分钟内有效、用完即废的一次性票据。</small
              ></span
            >
          </label>
        </div>
        <div v-if="error" class="error" role="alert">{{ error }}</div>
      </template>
    </form>
    <template #footer>
      <div class="settings-footer">
        <span class="save-status">{{
          saving ? '正在保存…' : loaded ? '关闭时保存本次修改' : '读取后即可修改设置'
        }}</span>
        <button type="button" class="ghost" :disabled="saving" @click="close">完成并关闭</button>
        <button class="primary" form="file-settings-form" :disabled="loading || !loaded || saving">
          {{ saving ? '保存中…' : '保存设置' }}
        </button>
      </div>
    </template>
  </SettingsDialogShell>
</template>

<style scoped>
.file-settings-body {
  padding: 26px;
}
.save-status {
  margin-right: auto;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.fields {
  display: grid;
  gap: 22px;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}
.fields label > span {
  display: block;
  margin-bottom: 7px;
  font-size: 12px;
  font-weight: 650;
}
.fields label > div {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fields input[type='number'] {
  width: 120px;
  height: 36px;
  border: 1px solid var(--wao-line-control);
  border-radius: 8px;
  background: var(--wao-bg-card);
  color: var(--wao-text);
  padding: 0 10px;
}
.fields b {
  font-size: 12px;
}
.fields small {
  display: block;
  margin-top: 6px;
  color: var(--wao-text-weak);
  font-size: 10px;
  line-height: 1.55;
}
.switch-row {
  grid-column: 1 / -1;
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 12px;
  border-radius: 10px;
  background: var(--wao-bg-sub);
}
.switch-row > input {
  margin-top: 2px;
}
.switch-row > span {
  margin: 0 !important;
}
.state {
  min-height: 190px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  color: var(--wao-text-weak);
  font-size: 12px;
}
.load-error {
  min-height: 190px;
  display: grid;
  justify-items: center;
  align-content: center;
  gap: 8px;
  color: var(--wao-danger-strong);
  font-size: 12px;
  text-align: center;
}
.load-error button {
  border: 0;
  background: transparent;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}
.error {
  margin-top: 14px;
  padding: 9px 10px;
  border-radius: 8px;
  background: var(--wao-danger-bg);
  color: var(--wao-danger-strong);
  font-size: 11px;
}
.settings-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.ghost,
.primary {
  height: 34px;
  padding: 0 14px;
  border-radius: 8px;
  cursor: pointer;
}
.ghost {
  border: 1px solid var(--wao-line-control);
  background: var(--wao-bg-card);
  color: var(--wao-text-2);
}
.primary {
  border: 0;
  background: var(--wao-primary);
  color: #fff;
}
.primary:disabled {
  opacity: 0.55;
  cursor: wait;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 650px) {
  .file-settings-body {
    padding: 20px 15px;
  }
  .fields {
    grid-template-columns: 1fr;
  }
  .settings-footer {
    flex-wrap: wrap;
  }
  .save-status {
    flex-basis: 100%;
  }
}
</style>
