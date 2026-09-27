<template>
  <section class="home-board" aria-labelledby="home-board-title">
    <div class="board-copy">
      <div class="board-eyebrow"><span></span>智能看板 · 灵感留白</div>
      <h1 id="home-board-title">这里是一个<span>看板</span></h1>
      <p class="board-description">有自动推荐能力的数据可以在这里显示。</p>
      <div class="board-empty">
        <i class="ri-sparkling-2-line" aria-hidden="true"></i>
        <p>暂时没有好的想法，先留空</p>
      </div>
    </div>

    <div class="board-scene" aria-hidden="true">
      <div class="board-aura"></div>
      <div class="board-orbit orbit-one"><i></i></div>
      <div class="board-orbit orbit-two"><i></i></div>
      <div class="board-orbit orbit-three"><i></i></div>
      <div class="board-core">
        <div class="core-shell"></div>
        <div class="core-inner"></div>
        <div class="core-light"></div>
      </div>
      <i class="board-spark spark-one"></i>
      <i class="board-spark spark-two"></i>
      <i class="board-spark spark-three"></i>
      <i class="board-spark spark-four"></i>
      <div class="board-float float-top"><i class="ri-bar-chart-2-line"></i><span>让重要信息<br /><b>自然浮现</b></span></div>
      <div class="board-float float-bottom"><i class="ri-add-line"></i><span>智能看板 <b>· 留白中</b></span></div>
      <div class="board-caption">A QUIET SPACE FOR WHAT MATTERS</div>
    </div>
  </section>
</template>

<style scoped>
.home-board {
  position: relative;
  isolation: isolate;
  display: grid;
  grid-template-columns: minmax(280px, 0.9fr) minmax(320px, 1.1fr);
  align-items: center;
  gap: clamp(12px, 3vw, 48px);
  min-height: min(690px, calc(100dvh - var(--wao-header-h) - var(--wao-tabbar-h) - 78px));
  padding: clamp(22px, 5vw, 72px) clamp(16px, 4.5vw, 68px);
  overflow: hidden;
  border: 1px solid var(--wao-line);
  border-radius: 22px;
  background:
    radial-gradient(ellipse at 76% 48%, rgba(104, 130, 255, 0.13), transparent 38%),
    radial-gradient(ellipse at 43% 100%, rgba(71, 207, 224, 0.08), transparent 34%),
    var(--wao-bg-card);
  color: var(--wao-text);
}
.home-board::before {
  position: absolute;
  z-index: -1;
  inset: 0;
  content: '';
  opacity: 0.36;
  pointer-events: none;
  background-image: radial-gradient(var(--wao-text-faint-2) 0.7px, transparent 0.7px);
  background-size: 27px 27px;
  mask-image: linear-gradient(90deg, transparent 0%, black 38%, black 94%, transparent);
}
.board-copy {
  position: relative;
  z-index: 1;
  max-width: 510px;
  animation: board-rise 0.8s cubic-bezier(0.2, 0.75, 0.25, 1) both;
}
.board-eyebrow {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--wao-primary-text);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
}
.board-eyebrow span {
  width: 22px;
  height: 1px;
  background: linear-gradient(90deg, var(--wao-primary), transparent);
}
.board-copy h1 {
  margin: 22px 0 16px;
  color: var(--wao-text);
  font-size: clamp(36px, 4.8vw, 64px);
  font-weight: 740;
  line-height: 1.14;
  letter-spacing: -0.055em;
}
.board-copy h1 span {
  display: block;
  color: var(--wao-primary);
  background: linear-gradient(100deg, var(--wao-primary) 8%, #8c72ed 65%, #3dbdd0);
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.board-description {
  max-width: 420px;
  margin: 0;
  color: var(--wao-text-2);
  font-size: clamp(14px, 1.35vw, 17px);
  line-height: 1.9;
}
.board-empty {
  display: flex;
  align-items: center;
  gap: 12px;
  max-width: 390px;
  margin-top: 28px;
  padding: 14px 17px;
  border: 1px solid var(--wao-line);
  border-radius: 14px;
  color: var(--wao-text-2);
  background: color-mix(in srgb, var(--wao-bg-card) 78%, transparent);
  box-shadow: 0 10px 32px rgba(48, 66, 125, 0.045);
  backdrop-filter: blur(12px);
}
.board-empty > i {
  flex: none;
  color: var(--wao-primary);
  font-size: 17px;
}
.board-empty p {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
}
.board-scene {
  position: relative;
  min-width: 0;
  width: 100%;
  aspect-ratio: 1.05;
  perspective: 1100px;
  transform-style: preserve-3d;
}
.board-aura {
  position: absolute;
  inset: 12%;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(107, 137, 255, 0.2), rgba(93, 197, 232, 0.1) 40%, transparent 70%);
  filter: blur(18px);
  animation: board-aura 8s ease-in-out infinite alternate;
}
.board-orbit {
  position: absolute;
  top: 48%;
  left: 50%;
  width: 70%;
  height: 37%;
  border: 1px solid color-mix(in srgb, var(--wao-primary) 25%, transparent);
  border-radius: 50%;
  transform-style: preserve-3d;
  box-shadow: 0 0 34px rgba(87, 117, 227, 0.08), inset 0 0 20px rgba(104, 142, 231, 0.05);
}
.orbit-one { transform: translate(-50%, -50%) rotateX(70deg) rotateZ(-27deg); animation: board-orbit-one 19s linear infinite; }
.orbit-two { width: 61%; height: 53%; border-color: rgba(75, 190, 210, 0.24); transform: translate(-50%, -50%) rotateX(67deg) rotateZ(44deg); animation: board-orbit-two 24s linear infinite; }
.orbit-three { width: 47%; height: 74%; border-color: rgba(161, 129, 240, 0.2); transform: translate(-50%, -50%) rotateY(69deg) rotateZ(16deg); animation: board-orbit-three 27s linear infinite; }
.board-orbit > i {
  position: absolute;
  top: 13%;
  left: 7%;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 0 4px rgba(115, 134, 251, 0.16), 0 0 24px 7px rgba(115, 134, 251, 0.56);
}
.board-core {
  position: absolute;
  top: 47%;
  left: 50%;
  width: clamp(150px, 19vw, 200px);
  aspect-ratio: 1;
  transform: translate(-50%, -50%) rotateX(-18deg) rotateY(23deg);
  transform-style: preserve-3d;
  animation: board-core-float 7s ease-in-out infinite;
}
.core-shell {
  position: absolute;
  inset: 4%;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.85);
  border-radius: 39% 61% 54% 46% / 46% 43% 57% 54%;
  transform: translateZ(12px) rotateZ(-18deg);
  background: radial-gradient(circle at 31% 25%, rgba(255, 255, 255, 0.99), rgba(225, 232, 255, 0.9) 17%, rgba(150, 170, 255, 0.74) 36%, rgba(106, 120, 242, 0.63) 58%, rgba(72, 192, 218, 0.58) 78%, rgba(255, 255, 255, 0.28));
  box-shadow: inset -18px -18px 40px rgba(63, 78, 188, 0.16), inset 14px 14px 33px rgba(255, 255, 255, 0.84), 0 24px 75px rgba(93, 110, 232, 0.23), 0 0 60px rgba(97, 196, 234, 0.13);
}
.core-shell::before {
  position: absolute;
  top: -19%;
  left: -5%;
  width: 72%;
  height: 43%;
  content: '';
  border-radius: 50%;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.97), transparent);
  filter: blur(2px);
  transform: rotate(-22deg);
}
.core-inner {
  position: absolute;
  inset: 21%;
  border: 1px solid rgba(255, 255, 255, 0.52);
  border-radius: 44% 56% 57% 43% / 42% 49% 51% 58%;
  transform: translateZ(35px) rotateZ(24deg);
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.3), rgba(119, 142, 250, 0.08) 45%, rgba(51, 195, 222, 0.2));
  box-shadow: inset 0 0 20px rgba(255, 255, 255, 0.55), 0 0 25px rgba(255, 255, 255, 0.21);
}
.core-light {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 23%;
  aspect-ratio: 1;
  border-radius: 50%;
  transform: translate(-50%, -50%) translateZ(58px);
  background: radial-gradient(circle at 34% 29%, #fff 0 8%, #e2e9ff 30%, #8ba1ff 64%, #5f71e3 100%);
  box-shadow: 0 0 18px #fff, 0 0 44px rgba(129, 150, 255, 0.75), 0 0 82px rgba(91, 215, 234, 0.55);
}
.board-spark {
  position: absolute;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 12px 3px rgba(111, 137, 255, 0.65);
  animation: board-spark 3.7s ease-in-out infinite;
}
.spark-one { top: 22%; left: 24%; animation-delay: -0.9s; }
.spark-two { top: 69%; left: 20%; width: 4px; height: 4px; animation-delay: -2.3s; }
.spark-three { top: 28%; right: 18%; width: 4px; height: 4px; animation-delay: -1.5s; }
.spark-four { right: 24%; bottom: 16%; animation-delay: -0.4s; }
.board-float {
  position: absolute;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 13px;
  border: 1px solid var(--wao-line);
  border-radius: 13px;
  color: var(--wao-text-2);
  background: color-mix(in srgb, var(--wao-bg-card) 76%, transparent);
  box-shadow: 0 14px 32px rgba(53, 72, 143, 0.1);
  backdrop-filter: blur(12px);
  font-size: 10px;
  animation: board-chip-float 6s ease-in-out infinite;
}
.board-float > i { color: var(--wao-primary); font-size: 15px; }
.board-float b { color: var(--wao-text); font-size: 11px; font-weight: 670; }
.float-top { top: 18%; right: 5%; animation-delay: -1.8s; }
.float-bottom { bottom: 14%; left: 8%; animation-delay: -4s; }
.board-caption {
  position: absolute;
  bottom: 2%;
  left: 50%;
  width: max-content;
  max-width: 95%;
  color: var(--wao-text-faint);
  transform: translateX(-50%);
  font-size: 9px;
  letter-spacing: 0.09em;
}
@keyframes board-rise { from { opacity: 0; transform: translateY(14px); } to { opacity: 1; transform: translateY(0); } }
@keyframes board-aura { from { transform: scale(0.88); opacity: 0.65; } to { transform: scale(1.08); opacity: 1; } }
@keyframes board-core-float { 0%,100% { transform: translate(-50%,-50%) rotateX(-18deg) rotateY(23deg) translateY(0); } 50% { transform: translate(-50%,-50%) rotateX(-12deg) rotateY(30deg) translateY(-12px); } }
@keyframes board-orbit-one { to { transform: translate(-50%,-50%) rotateX(70deg) rotateZ(333deg); } }
@keyframes board-orbit-two { to { transform: translate(-50%,-50%) rotateX(67deg) rotateZ(404deg); } }
@keyframes board-orbit-three { to { transform: translate(-50%,-50%) rotateY(69deg) rotateZ(376deg); } }
@keyframes board-chip-float { 0%,100% { transform: translateY(0); } 50% { transform: translateY(-6px); } }
@keyframes board-spark { 0%,100% { opacity: 0.28; transform: scale(0.7); } 50% { opacity: 1; transform: scale(1.2); } }
@media (max-width: 900px) {
  .home-board { grid-template-columns: minmax(0,1fr); grid-template-rows: auto minmax(280px,1fr); gap: 0; min-height: 620px; padding: 34px clamp(20px, 6vw, 52px) 18px; }
  .board-copy { max-width: 620px; }
  .board-scene { width: min(100%, 500px); margin: -18px auto -8px; }
}
@media (max-width: 520px) {
  .home-board { min-height: 640px; grid-template-rows: auto 300px; padding: 30px 18px 16px; border-radius: 16px; }
  .board-copy h1 { margin: 16px 0 11px; font-size: clamp(36px, 10vw, 48px); }
  .board-description { font-size: 14px; }
  .board-empty { margin-top: 19px; padding: 12px 13px; }
  .board-scene { width: 115%; max-width: 440px; margin: -4px 0 -12px -7.5%; }
  .board-core { width: 150px; }
  .board-float { padding: 8px 10px; }
  .float-top { right: 0; }
  .float-bottom { left: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .board-copy,
  .board-aura,
  .board-orbit,
  .board-core,
  .board-spark,
  .board-float { animation: none !important; }
}
</style>
