<script setup lang="ts">
/**
 * 工作项类型图标：主任务与子任务各一个，参照 ONES 的蓝底白标方块，
 * 列表、表格与详情弹框的面包屑共用这一份，保证同一类型处处长得一样。
 */
withDefaults(defineProps<{ kind: 'task' | 'subtask'; size?: number }>(), { size: 16 })
</script>

<template>
  <span
    class="work-item-icon"
    :class="kind"
    :style="{ width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.72)}px` }"
    :title="kind === 'task' ? '任务' : '子任务'"
    :aria-label="kind === 'task' ? '任务' : '子任务'"
    role="img"
  >
    <i :class="kind === 'task' ? 'ri-checkbox-line' : 'ri-node-tree'"></i>
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
</style>
