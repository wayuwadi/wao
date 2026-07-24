<script setup lang="ts">
const bullets = ['无限画布、便签与连线', '白板可嵌入项目总览', '从任务一键生成便签']
</script>

<template>
  <div class="wb">
    <div class="card">
      <div class="head">
        <div class="icon"><i class="ri-artboard-line"></i></div>
        <div>
          <div class="title">白板 / 画布</div>
          <div class="sub">本轮为架构占位 · 验证它在整体结构中的位置</div>
        </div>
        <div class="spacer"></div>
        <span class="chip">后续迭代</span>
      </div>
      <div class="desc">自由画布：头脑风暴、家庭年度规划图、旅行路线草图，都可以关联到项目与任务。</div>
      <div class="bullets">
        <div v-for="b in bullets" :key="b" class="bullet"><i class="ri-check-line"></i>{{ b }}</div>
      </div>
      <div class="canvas">
        <div class="note yellow">2027 家庭年度规划（便签示例）</div>
        <div class="note blue">滇西北路线草图 → 可关联到旅行项目</div>
        <div class="canvas-tag">画布预览 · 演示</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wb {
  padding: 16px 24px 40px;
  max-width: 880px;
  margin: 0 auto;
  animation: waoFadeUp 0.25s ease;
}
.card {
  background: #fff;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  padding: 26px 30px;
}
.head {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}
.spacer {
  flex: 1;
}
.icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: #f0f1f4;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--wao-text-3);
  font-size: 21px;
}
.title {
  font-size: 18px;
  font-weight: 700;
}
.sub {
  margin-top: 2px;
  font-size: 12px;
  color: var(--wao-text-weak);
}
.chip {
  font-size: 11.5px;
  background: #e9edfb;
  color: #3a4cc2;
  border-radius: 999px;
  padding: 3px 11px;
}
.desc {
  margin-top: 14px;
  font-size: 13.5px;
  line-height: 1.85;
  color: var(--wao-text-2);
}
.bullets {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.bullet {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--wao-text-3);
}
.bullet i {
  font-size: 14px;
  color: var(--wao-success);
}
.canvas {
  margin-top: 18px;
  height: 220px;
  border: 1px dashed #d9dce5;
  border-radius: 12px;
  background-image: radial-gradient(#e3e4ea 1px, transparent 1px);
  background-size: 16px 16px;
  position: relative;
  overflow: hidden;
}
.note {
  position: absolute;
  width: 150px;
  padding: 12px;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(16, 18, 28, 0.08);
  font-size: 12px;
  line-height: 1.6;
}
.note.yellow {
  left: 34px;
  top: 38px;
  background: #fef7dc;
  color: #6b5b18;
  transform: rotate(-2deg);
}
.note.blue {
  right: 52px;
  top: 86px;
  background: #e3f0fb;
  color: #2b4d6b;
  transform: rotate(1.5deg);
}
.canvas-tag {
  position: absolute;
  left: 50%;
  bottom: 14px;
  transform: translateX(-50%);
  font-size: 11px;
  color: var(--wao-text-faint);
  background: #fff;
  border-radius: 999px;
  padding: 2px 10px;
  border: 1px solid var(--wao-divider);
}
</style>
