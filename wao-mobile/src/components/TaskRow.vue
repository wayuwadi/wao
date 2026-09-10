<script setup lang="ts">
import type { Task } from "../domain/models";
import AppIcon from "./AppIcon.vue";
defineProps<{ task: Task; project?: string; busy?: boolean }>();
defineEmits<{ edit: [task: Task]; toggle: [task: Task] }>();
</script>
<template>
  <article class="task-row" :class="{ done: task.status === '已完成' }">
    <button
      class="task-check"
      :class="{ checked: task.status === '已完成' }"
      :aria-label="`${task.status === '已完成' ? '重新打开' : '完成'}：${task.title}`"
      :disabled="busy"
      @click="$emit('toggle', task)"
    >
      <AppIcon
        v-if="task.status === '已完成'"
        name="check"
        :size="16"
      /></button
    ><button class="task-body" @click="$emit('edit', task)">
      <span class="task-title">{{ task.title }}</span
      ><span class="task-meta"
        ><span v-if="project">{{ project }}</span
        ><span v-if="task.due">{{ task.due.slice(5).replace("-", " / ") }}</span
        ><span v-if="task.status !== '待办' && task.status !== '已完成'">{{
          task.status
        }}</span></span
      ></button
    ><span
      class="priority-dot"
      :class="task.pr.toLowerCase()"
      :aria-label="task.pr"
    />
  </article>
</template>
