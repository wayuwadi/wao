import { computed, ref } from "vue";
import {
  chatApi,
  uploadChatAttachment,
  type ChatInput,
  type ChatMessage,
} from "../api/chat";
type Reply = { id: string; authorName: string; preview: string };
type Draft = { text: string; reply: Reply | null; pending: ChatInput | null };
const empty = (): Draft => ({ text: "", reply: null, pending: null });
export function createChatStore(
  api = chatApi,
  storage: Pick<Storage, "getItem" | "setItem"> = localStorage,
) {
  const messages = ref<ChatMessage[]>([]),
    draft = ref<Draft>(empty()),
    account = ref(""),
    member = ref("");
  const error = ref(""),
    storageError = ref(""),
    loading = ref(false),
    sending = ref(false),
    uploading = ref(false),
    active = ref(true);
  let generation = 0,
    revision = 0,
    reading = false,
    corrupt = false;
  const unread = computed(
    () =>
      messages.value.filter(
        (m) => m.author.id !== member.value && !m.read && !m.deleted,
      ).length,
  );
  const key = () => `wao-mobile:chat:v1:${account.value}`;
  function persist(next: Draft) {
    if (!account.value || corrupt) return false;
    try {
      storage.setItem(key(), JSON.stringify(next));
      draft.value = next;
      storageError.value = "";
      return true;
    } catch {
      storageError.value =
        "本机草稿保存失败，请释放存储空间后重试；为避免丢失，暂不发送。";
      return false;
    }
  }
  function edit(text: string, reply = draft.value.reply) {
    const next = { ...draft.value, text, reply };
    if (!persist(next)) draft.value = next;
  }
  function reset(id: string, memberId: string) {
    generation++;
    revision++;
    account.value = id;
    member.value = memberId;
    messages.value = [];
    draft.value = empty();
    corrupt = false;
    error.value = "";
    storageError.value = "";
    loading.value = false;
    sending.value = false;
    uploading.value = false;
    reading = false;
    if (!id) return;
    try {
      const raw = storage.getItem(key());
      if (!raw) return;
      const value = JSON.parse(raw);
      if (
        typeof value.text !== "string" ||
        (value.reply &&
          (typeof value.reply.id !== "string" ||
            typeof value.reply.authorName !== "string" ||
            typeof value.reply.preview !== "string")) ||
        (value.pending &&
          (typeof value.pending.content !== "string" ||
            !/^[A-Za-z0-9_-]{8,64}$/.test(value.pending.clientToken) ||
            (value.pending.replyToId !== undefined &&
              typeof value.pending.replyToId !== "string")))
      )
        throw new Error();
      draft.value = {
        text: value.text,
        reply: value.reply || null,
        pending: value.pending || null,
      };
    } catch {
      corrupt = true;
      storageError.value =
        "本机聊天草稿读取失败。请先保留本机数据并重新登录重试。";
    }
  }
  function merge(message: ChatMessage) {
    revision++;
    const index = messages.value.findIndex((m) => m.id === message.id);
    if (index < 0) messages.value = [...messages.value, message].slice(-100);
    else
      messages.value = messages.value.map((m) =>
        m.id === message.id ? message : m,
      );
  }
  async function refresh() {
    if (!account.value || loading.value || !active.value) return;
    const ticket = generation,
      version = revision;
    loading.value = true;
    try {
      const result = await api.messages();
      if (ticket === generation && version === revision) {
        messages.value = result;
        error.value = "";
      }
    } catch (e) {
      if (ticket === generation)
        error.value = e instanceof Error ? e.message : "消息同步失败";
    } finally {
      if (ticket === generation) loading.value = false;
    }
  }
  async function send() {
    if (
      sending.value ||
      uploading.value ||
      !account.value ||
      storageError.value
    )
      return;
    const content = draft.value.text.trim();
    if (!draft.value.pending && (!content || [...content].length > 2000))
      return;
    const attempt = draft.value.pending || {
      clientToken: crypto.randomUUID(),
      content,
      ...(draft.value.reply ? { replyToId: draft.value.reply.id } : {}),
    };
    if (!persist({ ...draft.value, pending: attempt })) return;
    const ticket = generation;
    sending.value = true;
    error.value = "";
    try {
      const message = await api.send({ ...attempt });
      if (ticket !== generation) return;
      merge(message);
      const unchanged =
        draft.value.text.trim() === attempt.content &&
        (draft.value.reply?.id || undefined) === attempt.replyToId;
      // Clearing is durable too. If this fails the original token remains safe to retry.
      persist(unchanged ? empty() : { ...draft.value, pending: null });
    } catch (e) {
      if (ticket === generation)
        error.value = e instanceof Error ? e.message : "发送失败，请重试";
    } finally {
      if (ticket === generation) sending.value = false;
    }
  }
  async function read(through: string, visible: () => boolean) {
    if (
      reading ||
      !account.value ||
      !active.value ||
      !visible() ||
      !unread.value
    )
      return;
    const ticket = generation,
      ids = new Set<string>();
    for (const m of messages.value) {
      ids.add(m.id);
      if (m.id === through) break;
    }
    if (!messages.value.some((m) => m.id === through)) return;
    reading = true;
    try {
      await api.read(through);
      if (ticket === generation) {
        revision++;
        messages.value = messages.value.map((m) =>
          ids.has(m.id) ? { ...m, read: true } : m,
        );
      }
    } catch (e) {
      if (ticket === generation)
        error.value = e instanceof Error ? e.message : "已读同步失败";
    } finally {
      if (ticket === generation) reading = false;
    }
  }
  const reacting = new Set<string>();
  async function react(id: string, emoji: string) {
    const ticket = generation,
      token = `${ticket}:${id}:${emoji}`;
    if (reacting.has(token)) return;
    reacting.add(token);
    try {
      const message = await api.react(id, emoji);
      if (ticket === generation) merge(message);
    } catch (e) {
      if (ticket === generation)
        error.value = e instanceof Error ? e.message : "回应失败";
    } finally {
      reacting.delete(token);
    }
  }
  async function upload(file: File, image: boolean) {
    if (uploading.value || !account.value || storageError.value) return;
    const ticket = generation;
    uploading.value = true;
    error.value = "";
    try {
      const result = await uploadChatAttachment(file, image);
      if (ticket !== generation) return;
      const name = result.name.replace(/[\[\]()\r\n]/g, "_");
      const link = `${image ? "!" : ""}[${name}](/api/${image ? "images" : "files"}/${result.id}/${image ? "raw" : "download"})`;
      edit(`${draft.value.text}${draft.value.text ? "\n" : ""}${link}`);
    } catch (e) {
      if (ticket === generation)
        error.value = e instanceof Error ? e.message : "上传失败";
    } finally {
      if (ticket === generation) uploading.value = false;
    }
  }
  function retryStorage() {
    if (corrupt) reset(account.value, member.value);
    else persist({ ...draft.value });
  }
  return {
    messages,
    draft,
    account,
    member,
    error,
    storageError,
    loading,
    sending,
    uploading,
    active,
    unread,
    reset,
    edit,
    refresh,
    send,
    read,
    react,
    upload,
    retryStorage,
  };
}
export const chat = createChatStore();
