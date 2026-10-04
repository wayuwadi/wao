<script setup lang="ts">
import { onMounted } from 'vue'
import { completeReleaseUpdate } from '@/composables/releaseUpdateState'
// 刷新后仅更新顶栏状态；设置页与顶栏共用 VersionUpdateNotice 的卡片。
onMounted(completeReleaseUpdate)
</script>
<template><span hidden aria-hidden="true"></span></template>
