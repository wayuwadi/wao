<script setup lang="ts">
import { hubRepositories } from '@/config/hubRepositories'
</script>
<template>
  <section>
    <p class="hub-note">分享你的能力，也分享你的陪伴</p>
    <h1>WAO-HUB</h1>
    <p class="hub-lead">
      在同一个入口发现、创造和分享。选一个仓库，把喜欢的作品带回自己的工作空间。
    </p>
    <div class="hub-grid" style="margin-top: 32px">
      <RouterLink
        v-for="repo in hubRepositories"
        :key="repo.id"
        :to="repo.path"
        class="hub-card"
        style="color: inherit; text-decoration: none"
        ><i :class="repo.icon" style="font-size: 32px" aria-hidden="true"></i>
        <h2>{{ repo.title }}</h2>
        <p class="hub-lead">{{ repo.description }}</p>
        <span>进入仓库 →</span></RouterLink
      >
    </div>
  </section>
</template>
