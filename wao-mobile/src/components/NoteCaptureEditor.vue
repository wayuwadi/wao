<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, toRaw, watch } from "vue";
import { useWorkspace } from "../composables/workspace";
import {
  captureMarkdown,
  newCapture,
  type CaptureDraft,
} from "../domain/noteCapture";
import { saveCapture } from "../api/noteCapture";
import { ApiError } from "../api/client";
import { iosCapture, isIosCapture } from "../native/noteCapture";
import type { PluginListenerHandle } from "@capacitor/core";

const props = defineProps<{ initial: CaptureDraft }>();
const emit = defineEmits<{ close: []; saved: [] }>();
const w = useWorkspace(),
  account = w.session.value!.accountId,
  member = w.session.value!.member.id;
const owner = `${account}:${member}`,
  key = `wao-capture:v1:${owner}:${props.initial.id}`;
const draft = ref<CaptureDraft>(structuredClone(toRaw(props.initial))),
  error = ref(""),
  storageError = ref("");
const saving = ref(false),
  recording = ref(false),
  transcribing = ref(false),
  seconds = ref(0),
  saved = ref(false);
const starting = ref(false),
  conflicted = ref(false);
const imagePreview = ref("");
let restoreFailed = false;
let disposed = false,
  timer: ReturnType<typeof setInterval> | undefined,
  listener: PluginListenerHandle | undefined;
const sameMember = () =>
  w.session.value?.accountId === account &&
  w.session.value?.member.id === member;
const pendingAudio = computed(
  () =>
    draft.value.audioId &&
    !draft.value.transcribedAudioIds?.includes(draft.value.audioId),
);
const retainedAudio = computed(() =>
  (draft.value.audioIds ?? []).filter(
    (id) =>
      id !== draft.value.audioId &&
      !draft.value.transcribedAudioIds?.includes(id),
  ),
);
function persist() {
  if (restoreFailed) return;
  try {
    if (
      !draft.value.excerpt &&
      !draft.value.thoughts &&
      !draft.value.title &&
      !draft.value.sourceUrl &&
      !draft.value.audioId &&
      !draft.value.importedId &&
      !localStorage.getItem(key)
    )
      return;
    localStorage.setItem(key, JSON.stringify(draft.value));
    storageError.value = "";
  } catch {
    storageError.value = "本机草稿保存失败，请保持页面打开并重试保存。";
  }
}
watch(draft, persist, { deep: true, flush: "sync" });
async function transcribe(audioId: string) {
  if (transcribing.value || draft.value.transcribedAudioIds?.includes(audioId))
    return;
  transcribing.value = true;
  error.value = "";
  try {
    const result = await iosCapture.transcribe({ owner, audioId });
    if (disposed || !sameMember()) return;
    if (!result.text.trim())
      throw new Error("没有识别到文字，录音已保留，可重试。");
    draft.value.thoughts += `${draft.value.thoughts ? "\n\n" : ""}${result.text}`;
    draft.value.transcribedAudioIds = [
      ...(draft.value.transcribedAudioIds ?? []),
      audioId,
    ];
  } catch (e) {
    if (!disposed && sameMember())
      error.value = e instanceof Error ? e.message : "转写失败，录音已保留。";
  } finally {
    transcribing.value = false;
  }
}
async function start() {
  if (starting.value || recording.value || transcribing.value) return;
  starting.value = true;
  error.value = "";
  seconds.value = 0;
  const audioId = crypto.randomUUID();
  try {
    persist();
    if (storageError.value) throw new Error(storageError.value);
    await iosCapture.startRecording({ owner, id: audioId });
    if (disposed || !sameMember()) {
      await iosCapture.stopRecording();
      return;
    }
    draft.value.audioId = audioId;
    draft.value.audioIds = [...(draft.value.audioIds ?? []), audioId];
    recording.value = true;
    timer = setInterval(() => seconds.value++, 1000);
  } catch (e) {
    error.value =
      e instanceof Error ? e.message : "录音未能启动，请检查麦克风权限。";
  } finally {
    starting.value = false;
  }
}
async function stop() {
  try {
    const result = await iosCapture.stopRecording();
    recording.value = false;
    clearInterval(timer);
    await transcribe(result.audioId);
  } catch (e) {
    recording.value = false;
    clearInterval(timer);
    error.value =
      e instanceof Error ? e.message : "录音已中断，请检查保留的录音。";
  }
}
async function save() {
  if (
    starting.value ||
    pendingAudio.value ||
    recording.value ||
    transcribing.value ||
    saving.value ||
    restoreFailed ||
    !sameMember()
  )
    return;
  saving.value = true;
  error.value = "";
  persist();
  try {
    const imported = !!draft.value.importedId && isIosCapture();
    const note = imported
      ? (
          await iosCapture.saveImport({
            id: draft.value.importedId!,
            accountId: account,
            memberId: member,
            title: draft.value.title,
            thoughts: draft.value.thoughts,
          })
        ).note
      : await saveCapture(draft.value);
    if (disposed || !sameMember()) return;
    w.notes.value = [note, ...w.notes.value.filter((n) => n.id !== note.id)];
    // 网络不明后已恢复的首次创建可能返回旧正文；继续输入必须明确 PATCH，不能丢掉。
    if (!imported && note.content?.markdown !== captureMarkdown(draft.value)) {
      conflicted.value = true;
      throw new Error(
        "云端已经有这篇小记，内容发生了变化。当前输入仍保留；请在小记列表查看云端内容，也可以将当前输入另存一篇。",
      );
    }
    localStorage.removeItem(key);
    saved.value = true;
    for (const audioId of draft.value.audioIds ?? []) {
      if (!draft.value.transcribedAudioIds?.includes(audioId)) continue;
      try {
        await iosCapture.removeRecording({ owner, audioId });
      } catch {
        /* 文字已保存；保留音频不影响成功。 */
      }
    }
    emit("saved");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "保存失败，输入已保留。";
    if (
      (e instanceof ApiError && e.status === 409) ||
      error.value.includes("云端已有") ||
      error.value.includes("id 不合法")
    )
      conflicted.value = true;
    persist();
  } finally {
    saving.value = false;
  }
}
function close() {
  if (
    !starting.value &&
    !saving.value &&
    !recording.value &&
    !transcribing.value
  ) {
    persist();
    emit("close");
  }
}
function deferAudio() {
  draft.value.audioId = undefined;
  error.value =
    "录音仍保留在本机。可先保存文字，之后到本机收件箱恢复录音转写。";
}
function copy() {
  const next: CaptureDraft = {
    ...draft.value,
    id: newCapture().id,
    rev: undefined,
    importedId: undefined,
  };
  if (draft.value.importedId && isIosCapture()) {
    // 带原图的导入由原生复制队列，图片继续受 App Group 保护。
    void iosCapture
      .copyImport({
        id: draft.value.importedId,
        accountId: account,
        memberId: member,
        newId: next.id,
      })
      .then(() => {
        next.importedId = next.id;
        localStorage.setItem(
          `wao-capture:v1:${owner}:${next.id}`,
          JSON.stringify(next),
        );
        emit("close");
        window.dispatchEvent(
          new CustomEvent("wao:capture-open", { detail: next }),
        );
      })
      .catch((e) => {
        error.value = e instanceof Error ? e.message : "复制草稿失败";
      });
    return;
  }
  localStorage.setItem(
    `wao-capture:v1:${owner}:${next.id}`,
    JSON.stringify(next),
  );
  emit("close");
  window.dispatchEvent(new CustomEvent("wao:capture-open", { detail: next }));
}
onMounted(async () => {
  try {
    const raw = localStorage.getItem(key);
    if (raw) {
      const value = JSON.parse(raw);
      if (
        value.id !== props.initial.id ||
        typeof value.thoughts !== "string" ||
        typeof value.excerpt !== "string" ||
        typeof value.title !== "string"
      )
        throw new Error();
      draft.value = { ...draft.value, ...value };
    }
  } catch {
    restoreFailed = true;
    storageError.value = "本机草稿无法读取，原数据未覆盖，请勿清除应用数据。";
  }
  if (!storageError.value) persist();
  if (isIosCapture()) {
    if (draft.value.importedId) {
      try {
        const image = await iosCapture.previewImport({
          id: draft.value.importedId,
          accountId: account,
          memberId: member,
        });
        if (!disposed && sameMember()) imagePreview.value = image.dataUrl;
      } catch {
        error.value = "原图预览暂不可读取，导入仍保留在本机。";
      }
    }
    listener = await iosCapture.addListener(
      "recordingStopped",
      ({ audioId }: { audioId: string }) => {
        if (disposed || !sameMember() || draft.value.audioId !== audioId)
          return;
        recording.value = false;
        clearInterval(timer);
        void transcribe(audioId);
      },
    );
    if (disposed) void listener.remove();
  }
});
onUnmounted(() => {
  disposed = true;
  clearInterval(timer);
  void listener?.remove();
  if (recording.value) void iosCapture.stopRecording();
  if (!saved.value) persist();
});
</script>

<template>
  <div class="capture-backdrop">
    <section
      class="capture-sheet"
      role="dialog"
      aria-modal="true"
      aria-labelledby="capture-title"
    >
      <header>
        <h2 id="capture-title">
          {{ draft.excerpt ? "书摘与我的感想" : "记下此刻" }}
        </h2>
        <button
          type="button"
          :disabled="starting || saving || recording || transcribing"
          @click="close"
        >
          收起
        </button>
      </header>
      <div class="capture-body">
        <img
          v-if="imagePreview"
          :src="imagePreview"
          alt="收到的书摘原图"
          style="max-width: 100%; max-height: 280px; object-fit: contain"
        />
        <label
          >标题（可留空）<input
            v-model="draft.title"
            :disabled="saving"
            maxlength="200"
        /></label>
        <template v-if="draft.excerpt || draft.sourceUrl || draft.importedId"
          ><p class="capture-label">书摘 · 保留原文</p>
          <blockquote v-if="draft.excerpt">{{ draft.excerpt }}</blockquote>
          <p v-else class="muted">
            未收到书摘文字，可以先保存实际来源链接或原图。
          </p>
          <p v-if="draft.sourceTitle || draft.sourceUrl" class="muted">
            {{ draft.sourceTitle }} {{ draft.sourceUrl }}
          </p></template
        >
        <label
          >{{ draft.excerpt ? "我的感想（可留空）" : "我的想法"
          }}<textarea
            v-model="draft.thoughts"
            :disabled="saving"
            rows="7"
            placeholder="说一段，或者写下来…"
          />
        </label>
        <div v-if="isIosCapture()" class="record-actions">
          <button v-if="recording" type="button" @click="stop">
            结束录音 · {{ seconds }} 秒
          </button>
          <button
            v-else-if="pendingAudio"
            type="button"
            :disabled="transcribing"
            @click="transcribe(draft.audioId!)"
          >
            {{ transcribing ? "正在转文字…" : "重试转文字" }}
          </button>
          <button
            v-else
            type="button"
            :disabled="starting || saving || transcribing"
            @click="start"
          >
            {{
              starting ? "正在启动…" : draft.audioId ? "再录一段" : "录音记想法"
            }}
          </button>
          <small
            >结束后自动转文字；每段最长 60
            秒，切走或来电会停止并保留录音。</small
          >
        </div>
        <button
          v-if="pendingAudio && !transcribing && !recording"
          type="button"
          @click="deferAudio"
        >
          暂不转写，保留录音并继续写
        </button>
        <button
          v-for="audioId in retainedAudio"
          :key="audioId"
          type="button"
          :disabled="recording || transcribing || starting || saving"
          @click="
            draft.audioId = audioId;
            transcribe(audioId);
          "
        >
          继续转写保留的录音 · {{ audioId.slice(0, 8) }}
        </button>
        <p v-if="!isIosCapture()" class="muted">录音入口在 iOS 应用中可用。</p>
        <p v-if="error" role="alert" class="error">{{ error }}</p>
        <p v-if="storageError" role="alert" class="error">{{ storageError }}</p>
        <button v-if="conflicted || restoreFailed" type="button" @click="copy">
          将当前输入另存一篇
        </button>
      </div>
      <footer aria-live="polite">
        <span>{{
          saving
            ? "正在保存到服务器…"
            : storageError
              ? "本机草稿未保护"
              : "草稿留在本机"
        }}</span
        ><button
          class="primary"
          type="button"
          :disabled="
            restoreFailed ||
            starting ||
            pendingAudio ||
            saving ||
            recording ||
            transcribing ||
            !(
              draft.excerpt.trim() ||
              draft.thoughts.trim() ||
              draft.sourceUrl ||
              draft.importedId
            )
          "
          @click="save"
        >
          {{ saving ? "保存中…" : "保存小记" }}
        </button>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.capture-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: #252b3d66;
  display: flex;
  justify-content: center;
  align-items: flex-end;
}
.capture-sheet {
  background: var(--bg);
  width: min(100%, 620px);
  height: 100dvh;
  display: flex;
  flex-direction: column;
  padding: env(safe-area-inset-top) 0 env(safe-area-inset-bottom);
}
header,
footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px;
  border-bottom: 1px solid var(--line);
  flex-shrink: 0;
}
header h2 {
  font-size: 19px;
}
button {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 12px;
  background: white;
  min-height: 44px;
}
.capture-body {
  padding: 18px;
  overflow: auto;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
label {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-weight: 600;
}
blockquote {
  margin: 0;
  padding: 14px;
  border-left: 3px solid #6f73df;
  background: #eeeefd;
  border-radius: 0 10px 10px 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.8;
}
.record-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.record-actions small {
  color: var(--muted);
  line-height: 1.6;
}
footer {
  border-top: 1px solid var(--line);
  border-bottom: 0;
  font-size: 12px;
}
footer .primary {
  background: #6265cc;
  color: #fff;
}
.muted {
  overflow-wrap: anywhere;
}
.capture-label {
  font-size: 12px;
  color: #696cae;
}
</style>
