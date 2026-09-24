<script setup lang="ts">
/**
 * 工作项类型图标：主任务与子任务各一个，参照 ONES 的蓝底白标方块，
 * 列表、表格与详情弹框的面包屑共用这一份，保证同一类型处处长得一样。
 */
withDefaults(defineProps<{ kind: 'task' | 'subtask' | 'requirement' | 'bug'; size?: number }>(), { size: 16 })
</script>

<template>
  <span
    class="work-item-icon"
    :class="kind"
    :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.72)}px` }"
    :title="{ task: '任务', subtask: '子任务', requirement: '需求', bug: '缺陷' }[kind]"
    :aria-label="{ task: '任务', subtask: '子任务', requirement: '需求', bug: '缺陷' }[kind]"
    role="img"
  >
    <i :class="{ task: 'ri-checkbox-line', subtask: 'ri-node-tree', requirement: 'ri-lightbulb-line', bug: 'ri-bug-line' }[kind]"></i>
  </span>
</template>

<style scoped>
.work-item-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  border-radius: 3px;
  color: #fff;
  line-height: 1;
}
.work-item-icon.task {
  background: #307fe2;
}
.work-item-icon.subtask {
  background: #4aa3f0;
}
.work-item-icon.requirement { background: #3b9a62; }
.work-item-icon.bug { background: #dc5353; }
</style>
