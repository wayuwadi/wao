<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import { chat as c } from "../composables/chat";
import {
  assetPath,
  messageParts,
  saveChatFile,
  type ChatMessage,
} from "../api/chat";
import AppIcon from "./AppIcon.vue";
import ChatImage from "./ChatImage.vue";
const list = ref<HTMLElement>(),
  nearBottom = ref(true),
  attachment = ref<HTMLInputElement>(),
  picture = ref<HTMLInputElement>(),
  downloading = ref(""),
  fileError = ref("");
const length = computed(() => [...c.draft.value.text.trim()].length);
const reactions = [
  ["THUMBSUP", "👍"],
  ["HEART", "❤️"],
  ["SMILE", "😊"],
  ["DONE", "✅"],
] as const;
let mounted = false;
const visible = () =>
  mounted &&
  c.active.value &&
  document.visibilityState !== "hidden" &&
  nearBottom.value;
async function seen() {
  const last = c.messages.value.at(-1);
  if (last && visible()) await c.read(last.id, visible);
}
function scroll() {
  const el = list.value;
  if (el)
    nearBottom.value = el.scrollHeight - el.scrollTop - el.clientHeight < 64;
  void seen();
}
async function bottom() {
  nearBottom.value = true;
  await nextTick();
  list.value?.scrollTo({ top: list.value.scrollHeight });
  void seen();
}
watch(c.messages, async () => {
  await nextTick();
  if (nearBottom.value) void bottom();
});
watch(c.active, (value) => {
  if (value) void seen();
});
onMounted(() => {
  mounted = true;
  void c.refresh();
  void bottom();
});
onUnmounted(() => {
  mounted = false;
});
function reply(m: ChatMessage) {
  c.edit(c.draft.value.text, {
    id: m.id,
    authorName: m.author.name,
    preview: m.content.slice(0, 180),
  });
}
function choose(event: Event, image: boolean) {
  const input = event.target as HTMLInputElement,
    file = input.files?.[0];
  input.value = "";
  if (file) void c.upload(file, image);
}
async function download(url: string, name: string) {
  if (downloading.value) return;
  downloading.value = url;
  fileError.value = "";
  const account = c.account.value;
  try {
    await saveChatFile(url, name, () => mounted && account === c.account.value);
  } catch (e) {
    fileError.value = e instanceof Error ? e.message : "文件保存失败";
  } finally {
    downloading.value = "";
  }
}
function time(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString("zh-CN", {
        month: "numeric",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      });
}
</script>
<template>
  <section class="chat-panel" aria-label="团队聊天">
    <div class="chat-heading">
      <div>
        <p class="eyebrow">STAY IN TOUCH</p>
        <h1>聊聊今天。</h1>
      </div>
      <span class="chat-status">{{
        c.loading.value ? "正在同步…" : "团队聊天"
      }}</span>
    </div>
    <p class="chat-caption">与 Web、桌面端同步 · 最近 100 条消息</p>
    <p v-if="c.error.value || fileError" class="error" role="alert">
      {{ fileError || c.error.value }}
      <button class="text-button" @click="c.refresh">重新同步</button>
    </p>
    <p v-if="c.storageError.value" class="error" role="alert">
      {{ c.storageError.value }}
      <button class="text-button" @click="c.retryStorage">重试保存草稿</button>
    </p>
    <div
      ref="list"
      class="chat-messages"
      aria-label="消息列表"
      @scroll="scroll"
      @load.capture="nearBottom && bottom()"
    >
      <div v-if="!c.messages.value.length" class="empty">
        <AppIcon name="chat" :size="32" />
        <h3>
          {{ c.loading.value ? "正在连接聊天…" : "把日常，分享给彼此。" }}
        </h3>
        <p>发一句问候，或分享今天的小事。</p>
      </div>
      <article
        v-for="m in c.messages.value"
        :key="m.id"
        class="chat-message"
        :class="{ mine: m.author.id === c.member.value }"
      >
        <div class="chat-meta">
          <span>{{ m.author.name }}</span
          ><time :datetime="m.createdAt">{{ time(m.createdAt) }}</time>
        </div>
        <div class="chat-bubble">
          <span v-if="m.deleted" class="muted">这条消息已删除</span>
          <template v-else>
            <blockquote v-if="m.replyTo">
              <strong>{{ m.replyTo.authorName }}</strong
              ><span>{{ m.replyTo.preview }}</span>
            </blockquote>
            <template v-for="(part, i) in messageParts(m.content)" :key="i">
              <span v-if="part.kind === 'text'">{{ part.text }}</span>
              <ChatImage
                v-else-if="part.kind === 'image'"
                :url="part.url!"
                :name="part.text"
              />
              <button
                v-else-if="assetPath(part.url!)"
                class="chat-file"
                :disabled="!!downloading"
                @click="download(part.url!, part.text)"
              >
                <AppIcon name="download" :size="16" />{{
                  downloading === part.url ? "正在读取…" : part.text
                }}
              </button>
              <a
                v-else
                :href="part.url"
                target="_blank"
                rel="noopener noreferrer"
                >{{ part.text }} ↗</a
              >
            </template>
            <ChatImage
              v-if="m.imageUrl && !m.content.includes(m.imageUrl)"
              :url="m.imageUrl"
              :name="m.imageName || '聊天图片'"
            />
          </template>
        </div>
        <div v-if="!m.deleted" class="chat-actions">
          <button
            :aria-label="`回复 ${m.author.name} 的消息`"
            @click="reply(m)"
          >
            <AppIcon name="reply" :size="14" />回复</button
          ><button
            v-for="[kind, emoji] in reactions"
            :key="kind"
            :aria-label="`${emoji} 回应 ${m.author.name} 的消息`"
            :aria-pressed="
              !!m.reactions?.find((r) => r.emojiType === kind)?.reacted
            "
            @click="c.react(m.id, kind)"
          >
            {{ emoji
            }}<small
              v-if="m.reactions?.find((r) => r.emojiType === kind)?.count"
              >{{ m.reactions.find((r) => r.emojiType === kind)!.count }}</small
            ></button
          ><span v-if="m.author.id === c.member.value" class="chat-read">{{
            m.readCount > 0 ? `${m.readCount} 人已读` : "已发送"
          }}</span>
        </div>
      </article>
    </div>
    <button v-if="!nearBottom" class="chat-jump" @click="bottom">
      回到最新消息
      <span v-if="c.unread.value">· {{ c.unread.value }} 条未读</span>
    </button>
    <form class="chat-composer" @submit.prevent="c.send">
      <div v-if="c.draft.value.reply" class="chat-reply">
        <span
          >回复 {{ c.draft.value.reply.authorName
          }}<small>{{ c.draft.value.reply.preview }}</small></span
        ><button
          type="button"
          aria-label="取消引用"
          @click="c.edit(c.draft.value.text, null)"
        >
          <AppIcon name="close" :size="16" />
        </button>
      </div>
      <div
        v-if="c.draft.value.pending && !c.sending.value"
        class="chat-pending"
      >
        上一条发送结果未确认。重试会复用原消息，避免重复发送。
        <p>{{ c.draft.value.pending.content.slice(0, 100) }}</p>
      </div>
      <textarea
        :value="c.draft.value.text"
        aria-label="聊天消息"
        placeholder="说点什么，分享一个小日常…"
        rows="2"
        @input="c.edit(($event.target as HTMLTextAreaElement).value)"
      />
      <div class="chat-compose-tools">
        <div>
          <button
            type="button"
            class="icon-button"
            aria-label="添加图片"
            :disabled="c.uploading.value || !!c.storageError.value"
            @click="picture?.click()"
          >
            <AppIcon name="image" /></button
          ><button
            type="button"
            class="icon-button"
            aria-label="添加文件"
            :disabled="c.uploading.value || !!c.storageError.value"
            @click="attachment?.click()"
          >
            <AppIcon name="attach" /></button
          ><small :class="{ error: length > 2000 }">{{
            c.uploading.value ? "正在上传…" : `${length} / 2000`
          }}</small>
        </div>
        <button
          class="chat-send"
          :disabled="
            c.sending.value ||
            c.uploading.value ||
            !!c.storageError.value ||
            (!c.draft.value.pending && (!length || length > 2000))
          "
        >
          {{
            c.sending.value
              ? "发送中…"
              : c.draft.value.pending
                ? "重试发送"
                : "发送"
          }}<AppIcon name="send" :size="17" />
        </button>
      </div>
      <input
        ref="attachment"
        type="file"
        hidden
        @change="choose($event, false)"
      /><input
        ref="picture"
        type="file"
        accept="image/png,image/jpeg,image/gif,image/webp,image/heic,image/heif"
        hidden
        @change="choose($event, true)"
      />
    </form>
  </section>
</template>
