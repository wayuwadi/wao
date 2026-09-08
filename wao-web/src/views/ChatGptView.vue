<script setup lang="ts">
// 应用壳中的同一聊天实例投送到此处，切换 WAO 页签时仍保留正在接收的回复。
</script>

<template>
  <section id="chatgpt-workspace" aria-label="ChatGPT对话"></section>
</template>

<style scoped>
#chatgpt-workspace { position: relative; width: 100%; min-height: 420px; height: 100%; isolation: isolate; }
</style>
