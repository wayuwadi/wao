<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { fetchFileSettings, saveFileSettings, type FileSettings } from '@/api/system'
import { toast } from '@/composables/toast'

const emit = defineEmits<{
  close: []
  saved: [settings: FileSettings]
}>()

const form = reactive<FileSettings>({
  fileMaxMb: 50,
  imageMaxMb: 10,
  downloadCaptchaEnabled: true,
})
const loading = ref(true)
const loaded = ref(false)
const saving = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    Object.assign(form, await fetchFileSettings())
    loaded.value = true
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '文件设置读取失败，请重试'
  } finally {
    loading.value = false
  }
}

async function save() {
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

onMounted(load)
</script>

<template>
  <div class="mask" @click.self="emit('close')">
    <form class="dialog" @submit.prevent="save">
      <header>
        <div>
          <span>FILES & IMAGE STORE</span>
          <h2>文件与图床设置</h2>
        </div>
        <button type="button" aria-label="关闭" @click="emit('close')">
          <i class="ri-close-line"></i>
        </button>
      </header>

      <div v-if="loading" class="state"><i class="ri-loader-4-line spin"></i>正在读取真实设置…</div>
      <div v-else-if="!loaded" class="load-error" role="alert">
        <i class="ri-error-warning-line"></i><span>{{ error }}</span
        ><button type="button" @click="load">重新读取</button>
      </div>
      <template v-else>
        <div class="fields">
          <label>
            <span>普通文件上传上限</span>
            <div>
              <input v-model.number="form.fileMaxMb" type="number" min="1" max="50" required /><b
                >MB</b
              >
            </div>
            <small>1–50MB，不能超过当前网关与服务端的 50MB 部署基线。</small>
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

      <footer>
        <button type="button" class="ghost" @click="emit('close')">取消</button>
        <button class="primary" :disabled="loading || !loaded || saving">
          {{ saving ? '保存中…' : '保存设置' }}
        </button>
      </footer>
    </form>
  </div>
</template>

<style scoped>
.mask {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: rgba(18, 20, 30, 0.38);
  display: grid;
  place-items: center;
  padding: 18px;
}
.dialog {
  width: min(520px, 100%);
  max-height: calc(100vh - 36px);
  overflow: auto;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  background: var(--wao-bg-card);
  box-shadow: var(--wao-shadow-modal);
  padding: 20px;
}
.dialog header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}
.dialog header span {
  font-size: 9px;
  letter-spacing: 1.3px;
  color: var(--wao-primary-text);
  font-weight: 800;
}
.dialog h2 {
  margin: 4px 0 0;
  font-size: 19px;
}
.dialog header button {
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--wao-text-3);
  cursor: pointer;
}
.fields {
  display: grid;
  gap: 17px;
  margin-top: 22px;
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
.dialog footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 22px;
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
</style>
