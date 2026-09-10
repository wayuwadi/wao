<script setup lang="ts">
import pkg from "../../package.json";
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { App as NativeApp } from "@capacitor/app";
import { Capacitor, type PluginListenerHandle } from "@capacitor/core";
import { useWorkspace } from "../composables/workspace";
import { readDrafts } from "../composables/drafts";
import {
  editableNote,
  localDate,
  monthDays,
  newId,
  noteText,
  type Draft,
  type Task,
  type Note,
} from "../domain/models";
import AppIcon from "../components/AppIcon.vue";
import TaskRow from "../components/TaskRow.vue";
import DraftEditor from "../components/DraftEditor.vue";
const version = import.meta.env.VITE_MOBILE_VERSION || pkg.version;
const props = defineProps<{ tab: string }>(),
  w = useWorkspace(),
  router = useRouter();
const today = ref(localDate()),
  month = ref(new Date(new Date().getFullYear(), new Date().getMonth(), 1)),
  selected = ref(localDate()),
  search = ref(""),
  filter = ref("open"),
  mine = ref(true),
  editor = ref<Draft | null>(null),
  drafts = ref<Draft[]>([]),
  accountOpen = ref(false),
  toast = ref(""),
  localError = ref(""),
  loggingOut = ref(false);
const nav = [
  { id: "today", name: "今日", icon: "sun" },
  { id: "tasks", name: "待办", icon: "tasks" },
  { id: "calendar", name: "日历", icon: "calendar" },
  { id: "notes", name: "小记", icon: "notes" },
] as const;
const titles: Record<string, string> = {
  today: "今天，也从容一点。",
  tasks: "想做的，慢慢实现。",
  calendar: "给日常留个位置。",
  notes: "记下此刻的小事。",
};
const myOpen = computed(() =>
  w.myTasks.value.filter((t) => t.status !== "已完成"),
);
const dueToday = computed(() =>
  myOpen.value
    .filter((t) => t.due && t.due <= today.value)
    .sort((a, b) => a.due.localeCompare(b.due) || a.pr.localeCompare(b.pr)),
);
const doneToday = computed(
  () =>
    w.myTasks.value.filter(
      (t) => t.status === "已完成" && t.due === today.value,
    ).length,
);
const taskList = computed(() =>
  (mine.value ? w.myTasks.value : w.tasks.value)
    .filter(
      (t) =>
        (filter.value === "all" ||
          (filter.value === "done"
            ? t.status === "已完成"
            : t.status !== "已完成")) &&
        `${t.title} ${t.desc}`
          .toLowerCase()
          .includes(search.value.toLowerCase()),
    )
    .sort(
      (a, b) =>
        (a.due || "9999").localeCompare(b.due || "9999") ||
        a.pr.localeCompare(b.pr),
    ),
);
const noteList = computed(() =>
  w.myNotes.value.filter((n) =>
    `${n.title} ${noteText(n)}`
      .toLowerCase()
      .includes(search.value.toLowerCase()),
  ),
);
const days = computed(() => monthDays(month.value)),
  dayTasks = computed(() =>
    w.myTasks.value.filter((t) => t.due === selected.value),
  );
const dayCounts = computed(() => {
  const map = new Map<string, number>();
  w.myTasks.value.forEach((t) => map.set(t.due, (map.get(t.due) || 0) + 1));
  return map;
});
const dateLabel = computed(() =>
  new Date(`${today.value}T12:00:00`).toLocaleDateString("zh-CN", {
    month: "long",
    day: "numeric",
    weekday: "long",
  }),
);
function project(id: string) {
  return w.projects.value.find((p) => p.id === id)?.name || "项目";
}
function loadDrafts() {
  try {
    drafts.value = readDrafts(w.session.value!.accountId);
  } catch (e) {
    localError.value = e instanceof Error ? e.message : "读取草稿失败";
  }
}
function taskEditor(task: Task) {
  editor.value = drafts.value.find((d) => d.id === task.id) ?? {
    kind: "task",
    existing: true,
    id: task.id,
    title: task.title,
    project: task.project,
    pr: task.pr,
    owner: task.owner,
    due: task.due,
    desc: task.desc,
  };
}
function noteEditor(note: Note) {
  editor.value = drafts.value.find((d) => d.id === note.id) ?? {
    kind: "note",
    id: note.id,
    existing: true,
    title: note.title,
    text: noteText(note),
    rev: note.rev,
    readonly: !editableNote(note) || note.rev === undefined,
  };
}
function create(
  kind: "task" | "note" = props.tab === "notes" ? "note" : "task",
) {
  editor.value =
    kind === "task"
      ? {
          kind,
          existing: false,
          id: newId(),
          title: "",
          project: w.projects.value[0]?.id ?? "",
          pr: "P2",
          owner: w.session.value!.member.id,
          due: props.tab === "calendar" ? selected.value : today.value,
          desc: "",
        }
      : { kind, existing: false, id: newId(), title: "", text: "" };
}
let toastTimer: ReturnType<typeof setTimeout> | undefined;
function saved() {
  editor.value = null;
  loadDrafts();
  toast.value = "已保存，其他设备刷新后可见";
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (toast.value = ""), 4000);
}
function closeEditor() {
  if (w.saving.value) return;
  editor.value = null;
  setTimeout(loadDrafts, 0);
}
function shiftMonth(delta: number) {
  month.value = new Date(
    month.value.getFullYear(),
    month.value.getMonth() + delta,
    1,
  );
}
function goToday() {
  selected.value = today.value;
  month.value = new Date(new Date().getFullYear(), new Date().getMonth(), 1);
}
async function logout() {
  loggingOut.value = true;
  try {
    await w.logout();
  } catch (e) {
    localError.value = e instanceof Error ? e.message : "退出失败";
  } finally {
    loggingOut.value = false;
  }
}
watch(
  () => props.tab,
  () => {
    search.value = "";
    accountOpen.value = false;
  },
);
let backHandle: PluginListenerHandle | undefined,
  activeHandle: PluginListenerHandle | undefined;
function updateDate() {
  today.value = localDate();
}
const timer = setInterval(updateDate, 60_000);
onMounted(async () => {
  loadDrafts();
  if (Capacitor.isNativePlatform()) {
    backHandle = await NativeApp.addListener("backButton", () => {
      if (editor.value) closeEditor();
      else if (accountOpen.value) accountOpen.value = false;
      else if (props.tab !== "today") void router.replace("/today");
      else void NativeApp.minimizeApp();
    });
    activeHandle = await NativeApp.addListener(
      "appStateChange",
      ({ isActive }) => {
        if (isActive) {
          updateDate();
          void w.refresh();
        }
      },
    );
  }
});
onUnmounted(() => {
  clearInterval(timer);
  clearTimeout(toastTimer);
  void backHandle?.remove();
  void activeHandle?.remove();
});
</script>
<template>
  <div class="app-shell">
    <div class="workspace" :inert="!!editor">
      <header class="topbar">
        <RouterLink to="/today" class="wordmark"
          ><span class="small-brand">w<span>·</span></span
          ><strong>WAO</strong><span class="edition">日常</span></RouterLink
        >
        <div class="top-actions">
          <button
            class="icon-button"
            aria-label="刷新云端数据"
            :disabled="w.loading.value"
            @click="w.refresh"
          >
            <AppIcon
              name="refresh"
              :size="19"
              :class="{ spinning: w.loading.value }"
            /></button
          ><button
            class="avatar"
            aria-label="账户与版本"
            @click="accountOpen = !accountOpen"
          >
            {{
              w.session.value?.member.initial ||
              w.session.value?.member.name.slice(0, 1)
            }}
          </button>
        </div>
      </header>
      <div v-if="accountOpen" class="account-card">
        <strong>{{ w.session.value?.member.name }}</strong>
        <p class="muted">WAO 移动端 {{ version }} · 本机草稿按账号保留</p>
        <p class="muted">
          {{
            w.loadedAt.value ? `最近同步 ${w.loadedAt.value}` : "等待云端同步"
          }}
        </p>
        <button class="secondary" :disabled="loggingOut" @click="logout">
          <AppIcon name="logout" />{{ loggingOut ? "正在退出…" : "退出登录" }}
        </button>
      </div>
      <main>
        <div class="page-heading">
          <p class="eyebrow">
            {{
              props.tab === "today"
                ? "YOUR DAILY SPACE"
                : props.tab === "tasks"
                  ? "ONE THING AT A TIME"
                  : props.tab === "calendar"
                    ? "DAYS WITH INTENTION"
                    : "LITTLE THINGS, KEPT"
            }}
          </p>
          <h1>{{ titles[props.tab] }}</h1>
          <p class="muted">
            {{
              props.tab === "today"
                ? dateLabel
                : props.tab === "tasks"
                  ? "不必一次完成全部，先迈出一小步。"
                  : props.tab === "calendar"
                    ? "计划与留白，都是生活的一部分。"
                    : "灵感、心情，和不想忘记的瞬间。"
            }}
          </p>
        </div>
        <p v-if="w.error.value || localError" class="error banner" role="alert">
          {{ w.error.value || localError }}
          <button class="text-button" @click="w.refresh">重新加载</button>
        </p>
        <p v-if="w.loading.value" class="loading-line" role="status">
          正在同步你的日常…
        </p>
        <button
          v-if="drafts.length"
          class="draft-banner"
          @click="editor = drafts[0]!"
        >
          <AppIcon name="edit" /><span
            >你有 {{ drafts.length }} 份本机草稿<span class="muted"
              >继续写完那件小事</span
            ></span
          ><AppIcon name="right" />
        </button>
        <details v-if="drafts.length > 1" class="draft-list">
          <summary>查看其他草稿</summary>
          <button
            v-for="draft in drafts"
            :key="draft.id"
            class="text-button"
            @click="editor = draft"
          >
            {{ draft.kind === "task" ? "待办" : "小记" }} ·
            {{ draft.title || "未命名草稿" }}
          </button>
        </details>
        <template v-if="props.tab === 'today'"
          ><section class="today-hero">
            <div>
              <span class="hero-label"
                ><span class="live-dot" />给今天一点方向</span
              >
              <h2>
                {{
                  dueToday.length
                    ? "专注眼前，\n一步一步来。"
                    : "今天的留白，\n由你来安排。"
                }}
              </h2>
              <button class="hero-action" @click="create('task')">
                记一件待办<AppIcon name="plus" :size="17" />
              </button>
            </div>
            <div class="sun-art" aria-hidden="true">
              <div class="sun-disc" />
              <div class="sun-hill" />
              <div class="sun-line" />
            </div>
          </section>
          <section class="stats">
            <div>
              <span class="stat-icon green"
                ><AppIcon name="tasks" :size="18" /></span
              ><strong>{{ myOpen.length }}</strong
              ><span>待办事项</span>
            </div>
            <div>
              <span class="stat-icon amber"
                ><AppIcon name="sun" :size="18" /></span
              ><strong>{{ dueToday.length }}</strong
              ><span>今日关注</span>
            </div>
            <div>
              <span class="stat-icon violet"
                ><AppIcon name="notes" :size="18" /></span
              ><strong>{{ w.myNotes.value.length }}</strong
              ><span>我的小记</span>
            </div>
          </section>
          <div class="section-heading">
            <h2>
              今日关注 <span>{{ dueToday.length }}</span>
            </h2>
            <RouterLink to="/tasks"
              >全部待办<AppIcon name="right" :size="16"
            /></RouterLink>
          </div>
          <div class="task-list">
            <TaskRow
              v-for="task in dueToday.slice(0, 6)"
              :key="task.id"
              :task="task"
              :project="project(task.project)"
              :busy="w.toggling.value.has(task.id)"
              @edit="taskEditor"
              @toggle="w.toggle"
            />
            <div v-if="!dueToday.length && !w.loading.value" class="empty">
              <span class="empty-icon"><AppIcon name="sun" :size="26" /></span>
              <h3>{{ doneToday ? "今天的待办已完成" : "今天，留一点轻松" }}</h3>
              <p>添加一件小事，为今天定个方向。</p>
              <button class="text-button" @click="create('task')">
                ＋ 新建待办
              </button>
            </div>
          </div>
          <div class="section-heading">
            <h2>最近的小记</h2>
            <RouterLink to="/notes"
              >全部小记<AppIcon name="right" :size="16"
            /></RouterLink>
          </div>
          <p v-if="w.noteError.value" class="error">
            {{ w.noteError.value
            }}<button class="text-button" @click="w.refresh">重试</button>
          </p>
          <button
            v-for="note in w.myNotes.value.slice(0, 2)"
            :key="note.id"
            class="note-card"
            @click="noteEditor(note)"
          >
            <span class="note-stamp"
              ><AppIcon name="notes" :size="15" />{{
                note.updated.slice(0, 10).replaceAll("-", " / ")
              }}</span
            >
            <h3>{{ note.title }}</h3>
            <p>{{ noteText(note) }}</p>
            <AppIcon class="note-arrow" name="arrow" :size="18" /></button
          ><button
            v-if="!w.myNotes.value.length"
            class="quick-note"
            @click="create('note')"
          >
            <AppIcon name="notes" /><span
              >此刻有什么想记下的？<small
                >留住一个想法，只需要几句话。</small
              ></span
            ><AppIcon name="plus" /></button
        ></template>
        <template v-else-if="props.tab === 'tasks'"
          ><div class="search-box">
            <AppIcon name="search" /><input
              v-model="search"
              aria-label="搜索待办"
              placeholder="搜索你想做的事…"
            />
          </div>
          <div class="filter-row">
            <div class="segments">
              <button
                :class="{ active: filter === 'open' }"
                @click="filter = 'open'"
              >
                待完成</button
              ><button
                :class="{ active: filter === 'done' }"
                @click="filter = 'done'"
              >
                已完成</button
              ><button
                :class="{ active: filter === 'all' }"
                @click="filter = 'all'"
              >
                全部
              </button>
            </div>
            <button class="scope-button" @click="mine = !mine">
              {{ mine ? "我的" : "所有可见"
              }}<AppIcon name="filter" :size="15" />
            </button>
          </div>
          <div class="section-heading">
            <h2>
              {{ filter === "done" ? "做过的，都算数" : "每一小步，都算数" }}
              <span>{{ taskList.length }}</span>
            </h2>
          </div>
          <div class="task-list">
            <TaskRow
              v-for="task in taskList"
              :key="task.id"
              :task="task"
              :project="project(task.project)"
              :busy="w.toggling.value.has(task.id)"
              @edit="taskEditor"
              @toggle="w.toggle"
            />
            <div v-if="!taskList.length && !w.loading.value" class="empty">
              <span class="empty-icon"
                ><AppIcon name="tasks" :size="28"
              /></span>
              <h3>{{ search ? "没有找到相关待办" : "这里还没有待办" }}</h3>
              <p>
                {{ search ? "换个词试试看。" : "让想法落地，从第一件事开始。" }}
              </p>
              <button class="text-button" @click="create('task')">
                ＋ 新建待办
              </button>
            </div>
          </div></template
        >
        <template v-else-if="props.tab === 'calendar'"
          ><section class="calendar-card">
            <div class="month-header">
              <h2>
                {{ month.getFullYear() }}<span> / </span
                >{{ String(month.getMonth() + 1).padStart(2, "0") }}
              </h2>
              <div>
                <button class="text-button" @click="goToday">今天</button
                ><button
                  class="icon-button"
                  aria-label="上个月"
                  @click="shiftMonth(-1)"
                >
                  <AppIcon name="left" /></button
                ><button
                  class="icon-button"
                  aria-label="下个月"
                  @click="shiftMonth(1)"
                >
                  <AppIcon name="right" />
                </button>
              </div>
            </div>
            <div class="calendar-grid">
              <span
                v-for="day in ['一', '二', '三', '四', '五', '六', '日']"
                :key="day"
                class="weekday"
                >{{ day }}</span
              ><button
                v-for="day in days"
                :key="day.date"
                :aria-label="`${day.date}，${dayCounts.get(day.date) || 0} 件待办`"
                :aria-pressed="selected === day.date"
                :class="{
                  outside: !day.current,
                  selected: selected === day.date,
                  today: day.date === today,
                }"
                @click="selected = day.date"
              >
                <span>{{ day.day }}</span
                ><i v-if="dayCounts.get(day.date)" />
              </button>
            </div>
            <p class="calendar-legend"><i />有待办的日子 · 按截止日期展示</p>
          </section>
          <div class="section-heading">
            <h2>
              {{ selected.slice(5).replace("-", " 月 ") }} 日
              <span>{{ dayTasks.length }}</span>
            </h2>
            <button class="text-button" @click="create('task')">
              添加<AppIcon name="plus" :size="17" />
            </button>
          </div>
          <div class="task-list">
            <TaskRow
              v-for="task in dayTasks"
              :key="task.id"
              :task="task"
              :project="project(task.project)"
              :busy="w.toggling.value.has(task.id)"
              @edit="taskEditor"
              @toggle="w.toggle"
            />
            <div v-if="!dayTasks.length" class="empty">
              <span class="empty-icon"
                ><AppIcon name="calendar" :size="28"
              /></span>
              <h3>这一天，还很自由</h3>
              <p>为重要的事，留一个位置。</p>
              <button class="text-button" @click="create('task')">
                ＋ 安排一件事
              </button>
            </div>
          </div></template
        >
        <template v-else
          ><div class="search-box">
            <AppIcon name="search" /><input
              v-model="search"
              aria-label="搜索小记"
              placeholder="找回某个想法…"
            />
          </div>
          <p v-if="w.noteError.value" class="error" role="alert">
            {{ w.noteError.value
            }}<button class="text-button" @click="w.refresh">重试</button>
          </p>
          <div class="section-heading">
            <h2>
              我的片刻 <span>{{ noteList.length }}</span>
            </h2>
            <span class="muted">按最近更新</span>
          </div>
          <div class="notes-grid">
            <button
              v-for="(note, i) in noteList"
              :key="note.id"
              class="note-card"
              :class="{ peach: i % 3 === 1, lavender: i % 3 === 2 }"
              @click="noteEditor(note)"
            >
              <span class="note-stamp"
                ><AppIcon name="notes" :size="15" />{{
                  note.updated.slice(0, 10).replaceAll("-", " / ")
                }}</span
              >
              <h3>{{ note.title }}</h3>
              <p>{{ noteText(note) }}</p>
              <span v-if="note.tags?.length" class="note-tags">{{
                note.tags.map((t) => "#" + t).join(" ")
              }}</span
              ><AppIcon class="note-arrow" name="arrow" :size="18" />
            </button>
          </div>
          <div v-if="!noteList.length && !w.loading.value" class="empty">
            <span class="empty-icon"><AppIcon name="notes" :size="28" /></span>
            <h3>{{ search ? "没有找到这段记忆" : "小事，也值得被记住" }}</h3>
            <p>记一段心情，留一个灵感。</p>
            <button class="text-button" @click="create('note')">
              ＋ 写下第一篇小记
            </button>
          </div></template
        >
        <p class="page-footnote">一点秩序，一点自由。<span>WAO DAILY</span></p>
      </main>
      <button
        class="fab"
        :aria-label="props.tab === 'notes' ? '新建小记' : '新建待办'"
        @click="create()"
      >
        <AppIcon name="plus" :size="27" />
      </button>
      <nav class="bottom-nav" aria-label="主导航">
        <RouterLink
          v-for="item in nav"
          :key="item.id"
          :to="`/${item.id}`"
          :class="{ active: props.tab === item.id }"
          ><span><AppIcon :name="item.icon" :size="22" /></span>{{ item.name
          }}<i
        /></RouterLink>
      </nav>
    </div>
    <DraftEditor
      v-if="editor"
      :key="editor.id"
      :initial="editor"
      @close="closeEditor"
      @saved="saved"
    />
    <div v-if="toast" class="toast" role="status">
      <AppIcon name="check" :size="17" />{{ toast }}
    </div>
  </div>
</template>
