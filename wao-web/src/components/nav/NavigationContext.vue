<script setup lang="ts">
/**
 * 顶栏面包屑的第一段：只显示当前功能名。
 *
 * <p>原来旁边还有一个「项目 · 切换」下拉，用来在同一功能域里跳到别的功能；
 * 用户看不出它是干嘛的，左侧菜单已经能完成同样的跳转，所以去掉只留名字。
 */
defineProps<{ label: string }>()
</script>
<template>
  <div class="context-nav">
    <span>{{ label }}</span>
  </div>
</template>
<style scoped>
.context-nav {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.context-nav > span {
  white-space: nowrap;
}
</style>
