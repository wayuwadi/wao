import { computed, ref } from "vue";
import { api, ApiError } from "../api/client";
import type { Session, Project, Task, Note, Draft } from "../domain/models";
import { notePayload, noteText } from "../domain/models";
import { removeDraft } from "./drafts";
import { iosCapture, isIosCapture } from '../native/noteCapture';
const session = ref<Session | null>(null),
  tasks = ref<Task[]>([]),
  projects = ref<Project[]>([]),
  notes = ref<Note[]>([]);
const loading = ref(false),
  error = ref(""),
  noteError = ref(""),
  loadedAt = ref(""),
  saving = ref(false);
let generation = 0;
export function useWorkspace() {
  async function refresh() {
    const ticket = ++generation,
      account = session.value?.accountId;
    if (!account) return;
    loading.value = true;
    error.value = "";
    noteError.value = "";
    const results = await Promise.allSettled([
      api.tasks(),
      api.projects(),
      api.notes(),
    ]);
    if (ticket !== generation || session.value?.accountId !== account) return;
    if (results[0].status === "fulfilled") tasks.value = results[0].value;
    else error.value = results[0].reason.message;
    if (results[1].status === "fulfilled") projects.value = results[1].value;
    else error.value = results[1].reason.message;
    if (results[2].status === "fulfilled") notes.value = results[2].value;
    else noteError.value = results[2].reason.message;
    if (results.every((r) => r.status === "fulfilled"))
      loadedAt.value = new Date().toLocaleTimeString("zh-CN", {
        hour: "2-digit",
        minute: "2-digit",
      });
    loading.value = false;
  }
  function clear() {
    if (isIosCapture()) void iosCapture.clearSession().catch(() => { error.value = '共享登录清理失败，请保持主应用打开后重试退出'; });
    generation++;
    session.value = null;
    tasks.value = [];
    notes.value = [];
    projects.value = [];
    loading.value = false;
    loadedAt.value = "";
  }
  async function login(email: string, password: string) {
    session.value = await api.login(email, password);
    await refresh();
  }
  async function restore() {
    session.value = await api.session();
    if (session.value) await refresh();
  }
  async function logout() {
    if (isIosCapture()) await iosCapture.clearSession();
    await api.logout();
    clear();
  }
  async function save(draft: Draft) {
    const account = session.value?.accountId;
    if (!account) throw new Error("请先登录");
    if (saving.value) throw new Error("正在保存，请稍候");
    saving.value = true;
    try {
      if (draft.kind === "task") {
        const { title, project, pr, owner, due, desc } = draft;
        const saved = draft.existing
          ? await api.patchTask(draft.id, {
              title: title.trim(),
              pr,
              owner,
              due,
              desc,
            })
          : await api.createTask({
              title: title.trim(),
              project,
              pr,
              owner,
              due,
              desc,
            });
        if (session.value?.accountId === account)
          tasks.value = [
            saved,
            ...tasks.value.filter((t) => t.id !== saved.id),
          ];
      } else {
        if (draft.readonly) throw new Error("请在 Web 编辑富文本原文");
        if (draft.existing && draft.rev === undefined)
          throw new Error("缺少文档版本，重新打开后再编辑");
        let saved: Note;
        if (draft.existing) {
          saved = await api.patchNote(draft.id, notePayload(draft));
        } else {
          // A previous PUT may have succeeded while its response was lost.
          // Read the stable ID before retrying so a later Web edit is never overwritten.
          let previous: Note | null = null;
          try {
            previous = await api.note(draft.id);
          } catch (error) {
            if (!(error instanceof ApiError && error.status === 404))
              throw error;
          }
          if (previous) {
            const same =
              previous.author === session.value?.member.id &&
              !previous.content &&
              noteText(previous) === draft.text &&
              (!draft.title.trim() || previous.title === draft.title.trim());
            if (!same)
              throw new ApiError(
                "云端已存在这篇小记且内容不同。草稿已保留，请另存副本。",
                409,
              );
            saved = previous;
          } else {
            saved = await api.createNote(draft.id, notePayload(draft));
          }
        }
        if (session.value?.accountId === account)
          notes.value = [
            saved,
            ...notes.value.filter((n) => n.id !== saved.id),
          ];
      }
      removeDraft(account, draft.id);
    } finally {
      saving.value = false;
    }
  }
  const toggling = ref(new Set<string>());
  async function toggle(task: Task) {
    if (toggling.value.has(task.id)) return;
    const account = session.value?.accountId;
    toggling.value.add(task.id);
    try {
      const saved = await api.patchTask(task.id, {
        status: task.status === "已完成" ? "待办" : "已完成",
      });
      if (session.value?.accountId === account)
        tasks.value = tasks.value.map((t) => (t.id === saved.id ? saved : t));
    } catch (e) {
      error.value = e instanceof Error ? e.message : "保存失败";
      if (e instanceof ApiError && e.status === 401) clear();
    } finally {
      toggling.value.delete(task.id);
    }
  }
  return {
    session,
    tasks,
    projects,
    notes,
    loading,
    error,
    noteError,
    loadedAt,
    saving,
    toggling,
    refresh,
    clear,
    restore,
    login,
    logout,
    save,
    toggle,
    myTasks: computed(() =>
      tasks.value.filter((t) => t.owner === session.value?.member.id),
    ),
    myNotes: computed(() =>
      notes.value
        .filter((n) => n.author === session.value?.member.id)
        .sort((a, b) => b.updated.localeCompare(a.updated)),
    ),
  };
}
