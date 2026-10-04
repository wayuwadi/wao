<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from "vue";
import type { Note } from "../domain/models";
import { quoteParts } from "../domain/noteCapture";
import { saveCaptureThoughts } from "../api/noteCapture";
import { useWorkspace } from "../composables/workspace";
import { API_ORIGIN } from "../api/client";
import { Capacitor } from "@capacitor/core";

const props = defineProps<{ note: Note }>(),
  emit = defineEmits<{ close: [] }>();
const w = useWorkspace(),
  account = w.session.value!.accountId,
  member = w.session.value!.member.id;
const key = `wao-book-thoughts:v1:${account}:${member}:${props.note.id}`;
const current = ref(props.note),
  parts = computed(() => quoteParts(current.value)!);
const thoughts = ref(parts.value.thoughts),
  baseline = ref(current.value.rev),
  editing = ref(false),
  saving = ref(false),
  error = ref("");
let disposed = false,
  corrupt = false;
const unescape = (s: string) =>
  s
    .replace(/\\([\\`*_{}\[\]()<>#+.!|~-])/g, "$1")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
const excerpt = computed(() =>
  parts.value.prefix
    .split("\n")
    .filter((line) => line.startsWith("> "))
    .map((line) => unescape(line.slice(2)))
    .join("\n"),
);
const source = computed(() =>
  unescape(
    parts.value.prefix.split("\n").find((line) => line.startsWith("来源：")) ??
      "",
  ),
);
const images = computed(() =>
  [
    ...parts.value.prefix.matchAll(
      /!\[([^\n]*)\]\((\/api\/images\/[a-zA-Z0-9-]+\/(?:raw|preview))(?:\s+"[^"\n]*")?\)/g,
    ),
  ].map((m) => ({
    url: Capacitor.isNativePlatform() ? API_ORIGIN + m[2]! : m[2]!,
    label: m[1] === "书摘原图" ? "书摘原图" : "AI 文章配图",
  })),
);
function persist() {
  if (corrupt) return;
  try {
    localStorage.setItem(
      key,
      JSON.stringify({
        thoughts: thoughts.value,
        rev: baseline.value,
        prefix: parts.value.prefix,
      }),
    );
  } catch {
    error.value = "本机草稿保存失败，请保持页面打开。";
  }
}
watch(thoughts, persist);
function edit() {
  try {
    const raw = localStorage.getItem(key);
    if (raw) {
      const saved = JSON.parse(raw);
      if (typeof saved.thoughts !== "string" || typeof saved.rev !== "number")
        throw new Error();
      thoughts.value = saved.thoughts;
      baseline.value = saved.rev;
      if (saved.prefix !== parts.value.prefix)
        error.value =
          "书摘已在其他设备更新。你的感想草稿保留，保存前请重新核对。";
    }
  } catch {
    corrupt = true;
    error.value = "本机草稿无法读取，原数据已保留。";
  }
  editing.value = true;
}
async function save() {
  if (saving.value || corrupt || baseline.value === undefined) return;
  saving.value = true;
  error.value = "";
  persist();
  try {
    const note = await saveCaptureThoughts(
      current.value.id,
      baseline.value,
      parts.value.prefix,
      thoughts.value,
    );
    if (
      disposed ||
      w.session.value?.accountId !== account ||
      w.session.value.member.id !== member
    )
      return;
    current.value = note;
    baseline.value = note.rev;
    w.notes.value = w.notes.value.map((n) => (n.id === note.id ? note : n));
    localStorage.removeItem(key);
    editing.value = false;
  } catch (e) {
    error.value = e instanceof Error ? e.message : "保存失败，输入保留在本机。";
  } finally {
    saving.value = false;
  }
}
onUnmounted(() => {
  disposed = true;
  if (editing.value) persist();
});
</script>
<template>
  <div class="reader-backdrop">
    <section
      role="dialog"
      aria-modal="true"
      aria-label="阅读小记"
      class="book-reader"
    >
      <header>
        <h2>{{ current.title || "书摘与我的感想" }}</h2>
        <button :disabled="saving" @click="emit('close')">收起</button>
      </header>
      <main>
        <figure v-for="image in images" :key="image.url" style="margin: 0">
          <img :src="image.url" :alt="image.label" />
          <figcaption class="muted" style="text-align: center; font-size: 12px">
            {{ image.label }}
          </figcaption>
        </figure>
        <h3>书摘</h3>
        <blockquote>{{ excerpt }}</blockquote>
        <p v-if="source" class="muted">{{ source }}</p>
        <h3>我的感想</h3>
        <textarea
          v-if="editing"
          v-model="thoughts"
          :disabled="saving"
          rows="8"
          aria-label="我的感想"
        />
        <p v-else class="thoughts">
          {{ parts.thoughts || "暂时没有感想，也可以先把原文留住。" }}
        </p>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
      </main>
      <footer>
        <button
          v-if="!editing"
          :disabled="current.rev === undefined"
          @click="edit"
        >
          补充我的感想</button
        ><template v-else
          ><span>{{ saving ? "正在保存…" : "感想草稿留在本机" }}</span
          ><button :disabled="saving || corrupt" @click="save">
            保存感想
          </button></template
        >
      </footer>
    </section>
  </div>
</template>
<style scoped>
.reader-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1001;
  background: #252b3d66;
  display: flex;
  justify-content: center;
}
.book-reader {
  width: min(100%, 620px);
  height: 100dvh;
  background: var(--bg);
  display: flex;
  flex-direction: column;
  padding: env(safe-area-inset-top) 0 env(safe-area-inset-bottom);
}
header,
footer {
  padding: 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-shrink: 0;
}
header h2 {
  font-size: 19px;
  overflow-wrap: anywhere;
}
main {
  padding: 18px;
  overflow: auto;
  flex: 1;
  min-height: 0;
}
h3 {
  font-size: 14px;
  margin: 18px 0 12px;
  color: #696cae;
}
blockquote {
  margin: 0;
  padding: 14px;
  border-left: 3px solid #6f73df;
  background: #eeeefd;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.8;
}
.thoughts {
  white-space: pre-wrap;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
img {
  display: block;
  max-width: 100%;
  max-height: 420px;
  object-fit: contain;
  margin: auto;
}
button {
  min-height: 44px;
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 12px;
  background: white;
}
footer {
  border-top: 1px solid var(--line);
  font-size: 12px;
}
</style>
