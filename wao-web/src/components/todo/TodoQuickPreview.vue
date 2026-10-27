<script setup lang="ts">
import { ref } from 'vue'
import TodoQuickList from './TodoQuickList.vue'
const dark = ref(false)
</script>
<template>
  <div class="preview" :data-wao-theme="dark ? 'dark' : 'light'">
    <aside class="app-nav">
      <div class="brand">♧ WAO</div>
      <span>首页</span><span>小记</span><span>全部功能</span><small>公共</small><span>项目</span
      ><small>个人</small><b><i class="ri-checkbox-multiple-line"></i> 待办</b><span>日历</span
      ><span>知识库</span><span>草图</span>
    </aside>
    <div class="app-main">
      <div class="topbar">
        <span>首页　 /　 待办</span
        ><button @click="dark = !dark">{{ dark ? '浅色' : '深色' }}外观</button>
      </div>
      <main>
        <header>
          <h1>待办</h1>
          <span>按天</span><span>全部待办</span><b>随手清单</b><small>本地预览</small>
        </header>
        <TodoQuickList user-id="local-preview" />
      </main>
    </div>
  </div>
</template>
<style scoped>
.preview {
  min-height: 100vh;
  background: var(--wao-bg-page);
  color: var(--wao-text);
  display: grid;
  grid-template-columns: 210px minmax(0, 1fr);
  font-size: var(--wao-font-body);
}
.app-nav {
  display: flex;
  flex-direction: column;
  background: var(--wao-bg-side);
  border-right: 1px solid var(--wao-line);
  padding: var(--wao-space-2);
  gap: var(--wao-space-1);
}
.brand {
  font-size: var(--wao-font-title);
  color: var(--wao-primary);
  font-weight: 600;
  padding-bottom: var(--wao-space-3);
}
.app-nav span,
.app-nav b {
  padding: var(--wao-space-1);
}
.app-nav small {
  margin-top: var(--wao-space-3);
  color: var(--wao-text-3);
}
.app-nav b,
header b {
  background: var(--wao-primary-soft);
  color: var(--wao-primary-text);
  border-radius: var(--wao-radius-control);
}
.app-main {
  min-width: 0;
}
.topbar {
  height: 52px;
  border-bottom: 1px solid var(--wao-line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--wao-space-3);
  color: var(--wao-text-3);
}
.topbar button {
  background: transparent;
  border: 0;
  color: var(--wao-primary-text);
  cursor: pointer;
}
main {
  padding: var(--wao-space-3) var(--wao-space-4);
}
header {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--wao-space-2);
  margin-bottom: var(--wao-space-2);
}
h1 {
  font-size: var(--wao-font-title);
  margin: 0;
}
header span {
  color: var(--wao-text-3);
}
header b {
  padding: var(--wao-space-1) var(--wao-space-2);
}
header small {
  margin-left: auto;
  color: var(--wao-text-3);
}
@media (max-width: 1100px) {
  .preview {
    grid-template-columns: 150px minmax(0, 1fr);
  }
  main {
    padding: var(--wao-space-2);
  }
}
@media (max-width: 800px) {
  .preview {
    display: block;
  }
  .app-nav {
    display: none;
  }
  header {
    gap: var(--wao-space-1);
  }
}
</style>
