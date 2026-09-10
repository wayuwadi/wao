<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, nextTick } from "vue";
import type { Draft } from "../domain/models";
import { newId } from "../domain/models";
import { useWorkspace } from "../composables/workspace";
import { writeDraft, removeDraft } from "../composables/drafts";
import { ApiError } from "../api/client";
import AppIcon from "./AppIcon.vue";
const props = defineProps<{ initial: Draft }>(),
  emit = defineEmits<{ close: []; saved: [] }>(),
  w = useWorkspace();
const draft = ref<Draft>(JSON.parse(JSON.stringify(props.initial))),
  error = ref(""),
  storageError = ref(""),
  conflict = ref(false),
  saved = ref(false),
  titleInput = ref<HTMLInputElement>(),
  account = w.session.value!.accountId;
let previousFocus: HTMLElement | null = null;
function persist() {
  if (draft.value.kind === "note" && draft.value.readonly) return;
  try {
    writeDraft(account, draft.value);
    storageError.value = "";
  } catch {
    storageError.value = "本机存储不可用，请保持此页面并尽快保存到云端。";
  }
}
watch(draft, persist, { deep: true, flush: "sync" });
function close() {
  if (!w.saving.value) {
    persist();
    emit("close");
  }
}
async function save() {
  error.value = "";
  conflict.value = false;
  try {
    await w.save(draft.value);
    saved.value = true;
    emit("saved");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "保存失败";
    if (e instanceof ApiError) {
      conflict.value = e.status === 409;
      if (e.uncertain && draft.value.kind === "task" && !draft.value.existing)
        draft.value.uncertain = true;
    }
    persist();
  }
}
function copyNote() {
  if (draft.value.kind !== "note") return;
  const old = draft.value.id;
  draft.value = {
    ...draft.value,
    id: newId(),
    existing: false,
    rev: undefined,
    title: `${draft.value.title}（副本）`,
  };
  persist();
  try {
    removeDraft(account, old);
  } catch {}
  conflict.value = false;
  error.value = "已保留为新副本草稿，点击保存即可。";
}
function keydown(event: KeyboardEvent) {
  if (event.key === "Escape") {
    event.preventDefault();
    close();
  }
  if (event.key === "Tab") {
    const nodes = Array.from(
      document.querySelectorAll<HTMLElement>(
        ".sheet button:not([disabled]),.sheet input:not([disabled]),.sheet textarea:not([disabled]),.sheet select:not([disabled]),.sheet a",
      ),
    );
    const first = nodes[0],
      last = nodes.at(-1);
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first?.focus();
    }
  }
}
onMounted(async () => {
  previousFocus = document.activeElement as HTMLElement;
  document.addEventListener("keydown", keydown);
  await nextTick();
  titleInput.value?.focus();
});
onUnmounted(() => {
  document.removeEventListener("keydown", keydown);
  if (!saved.value) persist();
  previousFocus?.focus();
});
</script>
<template>
  <div class="sheet-backdrop">
    <section
      class="sheet"
      role="dialog"
      aria-modal="true"
      aria-labelledby="editor-title"
    >
      <div class="sheet-handle" />
      <header class="sheet-header">
        <div>
          <p class="eyebrow">MAKE ROOM FOR WHAT MATTERS</p>
          <h2 id="editor-title">
            {{ draft.existing ? "编辑" : "新建"
            }}{{ draft.kind === "task" ? "待办" : "小记" }}
          </h2>
        </div>
        <button
          class="icon-button"
          aria-label="收起并保留草稿"
          :disabled="w.saving.value"
          @click="close"
        >
          <AppIcon name="close" />
        </button>
      </header>
      <form @submit.prevent="save">
        <fieldset
          :disabled="w.saving.value || ('readonly' in draft && draft.readonly)"
        >
          <label
            >标题<input
              ref="titleInput"
              v-model="draft.title"
              :required="draft.kind === 'task'"
              maxlength="200"
              placeholder="一件想做的事，或一闪而过的灵感" /></label
          ><template v-if="draft.kind === 'task'"
            ><div class="form-grid">
              <label
                >归属项目<select
                  v-model="draft.project"
                  required
                  :disabled="draft.existing"
                >
                  <option value="" disabled>选择项目</option>
                  <option
                    v-for="p in w.projects.value"
                    :key="p.id"
                    :value="p.id"
                  >
                    {{ p.name }}
                  </option>
                </select></label
              ><label
                >优先级<select v-model="draft.pr">
                  <option value="P0">P0 · 紧急</option>
                  <option value="P1">P1 · 重要</option>
                  <option value="P2">P2 · 普通</option>
                  <option value="P3">P3 · 随时</option>
                </select></label
              >
            </div>
            <label
              >截止日期<input
                v-model="draft.due"
                type="date"
                :required="!draft.existing" /></label
            ><label
              >补充说明<textarea
                v-model="draft.desc"
                rows="6"
                placeholder="写下具体行动，让开始更简单…"
              />
            </label>
            <p v-if="!w.projects.value.length" class="error">
              还没有可用项目，请先在 WAO Web 中创建项目，再回来刷新。
            </p>
            <p v-if="draft.uncertain" class="error" role="alert">
              上次提交可能已经成功。请先收起并刷新待办确认，避免重复创建。<button
                type="button"
                class="text-button"
                @click="draft.uncertain = false"
              >
                我已检查，确实没有创建
              </button>
            </p></template
          ><label v-else
            >正文<textarea
              v-model="draft.text"
              rows="12"
              placeholder="今天有什么值得记下来？"
              :required="!draft.title.trim()"
            />
          </label>
        </fieldset>
        <p v-if="draft.kind === 'note' && draft.readonly" class="notice">
          这篇小记包含富文本，首版保持原文只读。<a
            href="https://wao.wayuwa.cn/#/notes"
            target="_blank"
            rel="noopener noreferrer"
            >前往 Web 编辑</a
          >
        </p>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <p v-if="storageError" class="error" role="alert">{{ storageError }}</p>
        <button
          v-if="conflict"
          type="button"
          class="secondary"
          @click="copyNote"
        >
          保留云端原文，将我的修改另存副本
        </button>
        <div class="sheet-footer">
          <span class="draft-status"
            ><AppIcon :name="storageError ? 'alert' : 'cloud'" :size="16" />{{
              w.saving.value
                ? "正在保存到云端…"
                : storageError
                  ? "草稿未备份"
                  : draft.kind === "note" && draft.readonly
                    ? "原文只读"
                    : "草稿留在本机"
            }}</span
          ><button
            v-if="!(draft.kind === 'note' && draft.readonly)"
            class="primary compact"
            :disabled="
              w.saving.value ||
              conflict ||
              (draft.kind === 'task' && (!draft.project || draft.uncertain))
            "
          >
            {{ w.saving.value ? "保存中…" : "保存"
            }}<AppIcon name="check" :size="18" />
          </button>
        </div>
      </form>
    </section>
  </div>
</template>
