<script setup lang="ts">
/**
 * 公开的文件下载页 —— 对外分享的链接落在这里，而不是直接给字节。
 *
 * 分享链接以前是 `/api/files/<id>/download`：谁拿到都能无限次拉，脚本一跑就是带宽账单。
 * 现在必须人点一次「下载」并答一道算术题，才换得到一张一次性票据（两分钟内有效、用完即废）。
 * 自己人在站内下载走的是登录会话，不用答题。
 */
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { apiDownloadChallenge, apiDownloadTicket, fetchFileMeta } from '@/api/board'
import type { FileItem } from '@/types'

const route = useRoute()
const fileId = String(route.params.id ?? '')

const file = ref<FileItem | null>(null)
const loadError = ref('')
const question = ref('')
const challengeId = ref('')
const answer = ref('')
const busy = ref(false)
const hint = ref('')
const done = ref(false)

onMounted(async () => {
  try {
    file.value = await fetchFileMeta(fileId)
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '文件不存在或已被删除'
    return
  }
  await newChallenge()
})

/** 取一道新题；答错、过期、下载完都要换一道，避免同一个答案被反复重放。 */
async function newChallenge() {
  answer.value = ''
  try {
    const c = await apiDownloadChallenge(fileId)
    challengeId.value = c.challengeId
    question.value = c.question
  } catch (error) {
    hint.value = error instanceof Error ? error.message : '获取验证码失败，请刷新重试'
  }
}

async function download() {
  if (busy.value || !answer.value.trim()) return
  busy.value = true
  hint.value = ''
  try {
    const { ticket } = await apiDownloadTicket(fileId, challengeId.value, answer.value)
    // 票据只能用一次，交给浏览器自己去取字节，前端不接管大文件的下载流
    window.location.assign(`/api/files/${encodeURIComponent(fileId)}/download?ticket=${encodeURIComponent(ticket)}`)
    done.value = true
    void newChallenge()
  } catch (error) {
    hint.value = error instanceof Error ? error.message : '验证失败，请重试'
    await newChallenge()
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="brand"><i class="ri-hard-drive-2-line"></i>WAO 文件下载</div>

      <div v-if="loadError" class="missing">
        <i class="ri-error-warning-line"></i>{{ loadError }}
      </div>

      <template v-else-if="file">
        <div class="file">
          <div class="file-icon"><i :class="file.icon || 'ri-file-line'"></i></div>
          <div class="file-main">
            <div class="file-name">{{ file.name }}</div>
            <div class="file-sub">{{ file.size }} · {{ file.time }}</div>
          </div>
        </div>

        <div class="captcha">
          <label class="label">请先完成验证</label>
          <div class="row">
            <span class="question wao-tabular">{{ question || '…' }}</span>
            <input
              v-model="answer"
              class="input"
              inputmode="numeric"
              autocomplete="off"
              placeholder="填写答案"
              @keydown.enter="download"
            />
            <button class="refresh" title="换一题" @click="newChallenge"><i class="ri-refresh-line"></i></button>
          </div>
          <div v-if="hint" class="hint bad">{{ hint }}</div>
          <div v-else-if="done" class="hint ok">已开始下载。再次下载需要重新验证。</div>
        </div>

        <button class="btn-primary" :disabled="busy || !answer.trim()" @click="download">
          <i class="ri-download-2-line"></i>{{ busy ? '验证中…' : '下载文件' }}
        </button>
        <div class="note">每次验证只能下载一次，链接被转发出去也不会被无限制地反复拉取。</div>
      </template>

      <div v-else class="missing">读取文件信息…</div>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--wao-bg-sub, #f5f6f9);
  padding: 24px;
}
.card {
  width: min(420px, 100%);
  background: var(--wao-bg-card);
  border-radius: 14px;
  box-shadow: var(--wao-shadow-modal, 0 12px 40px rgba(23, 25, 35, 0.12));
  padding: 22px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13px;
  font-weight: 600;
  color: var(--wao-text-2);
}
.brand i {
  color: var(--wao-primary);
  font-size: 16px;
}
.missing {
  margin-top: 18px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--wao-text-weak);
}
.file {
  margin-top: 18px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--wao-divider);
  border-radius: 10px;
}
.file-icon {
  width: 38px;
  height: 38px;
  border-radius: 9px;
  background: var(--wao-primary-soft, #e9edfb);
  color: var(--wao-primary, #4358d6);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}
.file-main {
  min-width: 0;
}
.file-name {
  font-size: 14px;
  font-weight: 600;
  word-break: break-all;
}
.file-sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.captcha {
  margin-top: 18px;
}
.label {
  font-size: 12px;
  color: var(--wao-text-weak);
}
.row {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.question {
  height: 38px;
  min-width: 92px;
  padding: 0 12px;
  border-radius: 9px;
  background: var(--wao-divider, #eef0f4);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 1px;
  user-select: none;
}
.input {
  flex: 1;
  min-width: 0;
  height: 38px;
  box-sizing: border-box;
  border: 1px solid var(--wao-line-control, #d9dce5);
  border-radius: 9px;
  padding: 0 12px;
  font-size: 14px;
  outline: none;
}
.input:focus {
  border-color: var(--wao-primary, #4358d6);
  box-shadow: 0 0 0 3px rgba(67, 88, 214, 0.12);
}
.refresh {
  width: 38px;
  height: 38px;
  border: 1px solid var(--wao-line-control, #d9dce5);
  background: var(--wao-bg-card);
  border-radius: 9px;
  cursor: pointer;
  color: var(--wao-text-3);
}
.refresh:hover {
  background: var(--wao-bg-hover-2, #f4f5f8);
}
.hint {
  margin-top: 8px;
  font-size: 12px;
}
.hint.bad {
  color: var(--wao-danger-strong, #bc4141);
}
.hint.ok {
  color: var(--wao-success-strong, #257a50);
}
.btn-primary {
  margin-top: 16px;
  width: 100%;
  height: 40px;
  border: none;
  border-radius: 9px;
  background: var(--wao-primary, #4358d6);
  color: #fff;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
}
.btn-primary:hover:not(:disabled) {
  background: var(--wao-primary-hover, #3a4cc2);
}
.btn-primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.note {
  margin-top: 12px;
  font-size: 11.5px;
  color: var(--wao-text-weak);
  line-height: 1.7;
}
</style>
