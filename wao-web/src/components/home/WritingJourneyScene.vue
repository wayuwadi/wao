<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{
  kind: 'room' | 'book' | 'chase'
  paused: boolean
  caught: boolean
  leaving: boolean
  canCatch: boolean
}>()
const emit = defineEmits<{ catch: [] }>()
const scene = computed(
  () => ({ room: 'wj-room', book: 'wj-book-scene', chase: 'wj-chase-scene' })[props.kind],
)
const colors = ['blue', 'yellow', 'pink', 'green']
</script>
<template>
  <div class="wj-scene" :class="[scene, { paused, caught, leaving }]">
    <div class="wj-wash" aria-hidden="true"></div>
    <div class="wj-theater">
      <template v-if="kind === 'room'"
        ><div aria-hidden="true" class="wj-ground"></div>
        <div aria-hidden="true" class="wj-desk"></div>
        <div aria-hidden="true" class="wj-desk-paper"></div>
        <div aria-hidden="true" class="wj-pencil"></div>
        <div aria-hidden="true" class="wj-lamp"></div>
        <div aria-hidden="true" class="wj-light"></div>
        <span aria-hidden="true" class="wj-idea-label wj-label-one">一个想法</span
        ><span aria-hidden="true" class="wj-idea-label wj-label-two">一段提示词</span
        ><span aria-hidden="true" class="wj-idea-label wj-label-three">想做的功能</span></template
      >
      <template v-else-if="kind === 'book'"
        ><div aria-hidden="true" class="wj-ground"></div>
        <div aria-hidden="true" class="wj-fly-leaf a"></div>
        <div aria-hidden="true" class="wj-fly-leaf b"></div>
        <div aria-hidden="true" class="wj-fly-leaf c"></div>
        <div aria-hidden="true" class="wj-big-book">
          <div aria-hidden="true" class="wj-leaf"></div>
          <div aria-hidden="true" class="wj-page-door"></div>
          <div aria-hidden="true" class="wj-cover">
            <span>小记</span>
            <div aria-hidden="true" class="wj-page-corner"></div>
          </div></div
      ></template>
      <template v-else
        ><div aria-hidden="true" class="wj-chase-floor"></div>
        <div aria-hidden="true" class="wj-trail"></div>
        <div aria-hidden="true" class="wj-landing-page"></div>
        <div aria-hidden="true" class="wj-orb"></div>
        <div aria-hidden="true" class="wj-net"></div
      ></template>
      <div
        v-for="color in colors"
        :key="color"
        class="wj-actor"
        :class="`wj-${color}`"
        aria-hidden="true"
      >
        <div class="wj-foot l"></div>
        <div class="wj-foot r"></div>
        <div class="wj-hand l"></div>
        <div class="wj-hand r"></div>
        <div class="wj-body"></div>
        <div v-if="color === 'blue'" class="wj-hat"></div>
        <div class="wj-eyes"><i></i><i></i></div>
        <div class="wj-smile"></div>
      </div>
      <button
        v-if="canCatch"
        class="wj-catch"
        type="button"
        aria-label="接住这个小念头"
        @click="emit('catch')"
      >
        <span>点一下，接住它</span>
      </button>
    </div>
  </div>
</template>
<style scoped>
.wj-scene {
  color-scheme: light;
  --wj-bg: light-dark(#fff, #181a24);
  --wj-side: light-dark(#fafafa, #202330);
  --wj-ink: light-dark(#252537, #f0edff);
  --wj-muted: light-dark(#777b8b, #aeb4c9);
  --wj-line: light-dark(#e8e9f0, #34394f);
  --wj-accent: light-dark(#5659df, #afb2ff);
  --wj-soft: light-dark(#f1f1ff, #30344d);
  --wj-paper: light-dark(#fffdf8, #34303b);
  --wj-floor: light-dark(#f1ebe2, #262738);
  --wj-shadow: light-dark(#38316520, #06071455);
  --wj-glow: light-dark(#fff1b8, #564a41);
  --wj-duration: 5800ms;
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}
:global([data-theme='dark']) .wj-scene { color-scheme: dark; }
.wj-wash {
  position: absolute;
  inset: 0;
  background: radial-gradient(ellipse at 52% 43%, var(--wj-glow), transparent 55%);
  opacity: 0.8;
}
.wj-actor {
  position: relative;
  width: 82px;
  height: 112px;
  flex: none;
  transform-origin: 50% 90%;
}
.wj-body {
  position: absolute;
  inset: 7px 1px 6px;
  border-radius: 44% 46% 30% 30%;
  background: radial-gradient(ellipse at 30% 17%, var(--wj-hi), var(--wj-c) 65%, var(--wj-low));
  box-shadow:
    inset -8px -7px 16px #18244116,
    inset 4px 3px 8px #fff4,
    0 9px 8px -7px var(--wj-shadow);
}
.wj-body::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background-image:
    radial-gradient(#ffffff40 0.7px, transparent 0.9px),
    radial-gradient(#10202020 0.5px, transparent 0.8px);
  background-size:
    4px 5px,
    3px 4px;
  opacity: 0.27;
}
.wj-actor .wj-eyes {
  position: absolute;
  left: 25%;
  top: 45%;
  width: 49%;
  display: flex;
  justify-content: space-between;
  z-index: 2;
}
.wj-eyes i {
  display: block;
  width: 8px;
  height: 13px;
  background: #17222c;
  border-radius: 50%;
  box-shadow: inset 1px 2px 2px #ffffff38;
}
.wj-smile {
  position: absolute;
  left: 41%;
  top: 68%;
  width: 17px;
  height: 9px;
  border: 1.7px solid #25312e;
  border-top: 0;
  border-radius: 0 0 20px 20px;
  transform: rotate(-4deg);
}
.wj-hand {
  position: absolute;
  width: 15px;
  height: 36px;
  border-radius: 70%;
  background: linear-gradient(100deg, var(--wj-c), var(--wj-hi));
  top: 57%;
  z-index: -1;
}
.wj-hand.l {
  left: -7px;
  transform: rotate(22deg);
}
.wj-hand.r {
  right: -7px;
  transform: rotate(-25deg);
}
.wj-foot {
  position: absolute;
  bottom: 0;
  width: 25px;
  height: 12px;
  background: var(--wj-low);
  border-radius: 50%;
  z-index: -1;
}
.wj-foot.l {
  left: 9px;
  transform: rotate(-8deg);
}
.wj-foot.r {
  right: 9px;
  transform: rotate(8deg);
}
.wj-blue {
  --wj-c: #3bbfe1;
  --wj-hi: #a7ecf5;
  --wj-low: #159cbc;
}
.wj-hat {
  position: absolute;
  left: -4px;
  right: -4px;
  top: 0;
  height: 18px;
  z-index: 4;
  background: radial-gradient(at 32% 17%, #334243, #111c1c 70%);
  border-radius: 50%;
  transform: rotate(-6deg);
  box-shadow: 0 3px 3px #08212a18;
}
.wj-hat::before {
  content: '';
  position: absolute;
  width: 9px;
  height: 7px;
  border-radius: 50%;
  left: 44%;
  top: -3px;
  background: #1d2929;
}
.wj-yellow {
  --wj-c: #ead55d;
  --wj-hi: #fff5b2;
  --wj-low: #d4b73e;
  height: 131px;
  width: 78px;
}
.wj-yellow .wj-body {
  border-radius: 52% 52% 27% 27%;
}
.wj-yellow .wj-eyes {
  top: 45%;
  left: 18%;
  width: 66%;
  gap: 1px;
}
.wj-yellow .wj-eyes i {
  width: 25px;
  height: 30px;
  border: 2px solid #454537;
  background: transparent;
  border-radius: 50%;
  position: relative;
  box-shadow: none;
}
.wj-yellow .wj-eyes i::after {
  content: '';
  position: absolute;
  width: 12px;
  height: 4px;
  border-bottom: 1.5px solid #454537;
  border-radius: 50%;
  top: 10px;
  left: 4px;
}
.wj-pink {
  --wj-c: #ef67d3;
  --wj-hi: #ffc5ed;
  --wj-low: #d94cbb;
  width: 88px;
  height: 100px;
}
.wj-pink .wj-body {
  border-radius: 50% 50% 43% 43%;
  clip-path: polygon(0 0, 37% 0, 50% 12%, 64% 0, 100% 0, 100% 100%, 0 100%);
}
.wj-pink .wj-eyes {
  left: 18%;
  width: 64%;
  top: 39%;
  gap: 0;
}
.wj-pink .wj-eyes i {
  width: 28px;
  height: 29px;
}
.wj-green {
  --wj-c: #b8d966;
  --wj-hi: #ecf7bd;
  --wj-low: #97bc43;
  height: 97px;
}
.wj-green .wj-eyes {
  left: 10%;
  top: 5%;
  width: 80%;
}
.wj-green .wj-eyes i {
  height: 28px;
  width: 28px;
  border: 6px solid #e3efc1;
  box-shadow:
    0 0 0 5px #bedb72,
    inset 3px 3px 4px #8c9280;
}
.wj-green .wj-smile {
  top: 67%;
  width: 29px;
  left: 33%;
}
.wj-theater {
  position: absolute;
  left: 8%;
  right: 8%;
  top: 30%;
  height: 57%;
  container-type: inline-size;
}
.wj-theater .wj-actor {
  position: absolute;
  z-index: 5;
  width: 13cqw;
  height: 17cqw;
  max-width: 104px;
  max-height: 139px;
}
.wj-theater .wj-yellow {
  height: 20cqw;
  max-height: 155px;
}
.wj-theater .wj-green {
  height: 15cqw;
  max-height: 119px;
}
.wj-theater .wj-pink {
  height: 16cqw;
  max-height: 124px;
}
.wj-theater .wj-eyes {
  transform: scale(0.94);
}
.wj-ground {
  position: absolute;
  left: 9%;
  right: 9%;
  height: 26%;
  bottom: 0;
  border-radius: 50%;
  background: radial-gradient(ellipse, var(--wj-floor), transparent 72%);
}
.wj-desk {
  position: absolute;
  left: 22%;
  top: 38%;
  width: 57%;
  height: 49%;
  background: linear-gradient(165deg, light-dark(#f0e3d3, #4b4051), light-dark(#e8dac8, #393143));
  border-radius: 50%;
  transform: rotateX(44deg) rotateZ(-5deg);
  box-shadow:
    0 17px 0 -6px light-dark(#d7c7b5, #312b37),
    0 45px 40px -25px var(--wj-shadow);
  opacity: 0;
}
.wj-desk-paper {
  position: absolute;
  left: 35%;
  top: 40%;
  width: 34%;
  height: 40%;
  border-radius: 4px;
  background: var(--wj-bg);
  box-shadow:
    0 3px 0 var(--wj-line),
    0 16px 30px var(--wj-shadow);
  transform: rotate(-6deg);
  z-index: 4;
  opacity: 0;
}
.wj-desk-paper::after {
  content: '';
  position: absolute;
  left: 16%;
  right: 16%;
  top: 28%;
  height: 32%;
  background: repeating-linear-gradient(to bottom, var(--wj-line) 0 1px, transparent 1px 15px);
  opacity: 0.7;
}
.wj-pencil {
  position: absolute;
  z-index: 7;
  width: 31%;
  height: 14px;
  left: 43%;
  top: 60%;
  background: linear-gradient(to bottom, #d9c6ff 0 25%, #8e7ace 26% 70%, #65529d 71%);
  border-radius: 4px;
  transform: rotate(-30deg);
  opacity: 0;
  filter: drop-shadow(0 7px 4px #44336125);
}
.wj-pencil::before {
  content: '';
  position: absolute;
  left: -18px;
  top: 0;
  border-right: 18px solid #ddbfa1;
  border-top: 7px solid transparent;
  border-bottom: 7px solid transparent;
}
.wj-pencil::after {
  content: '';
  position: absolute;
  left: -19px;
  top: 5px;
  width: 5px;
  height: 4px;
  background: #33314e;
  clip-path: polygon(0 50%, 100% 0, 100% 100%);
}
.wj-lamp {
  position: absolute;
  left: 72%;
  top: 25%;
  z-index: 3;
  width: 60px;
  height: 110px;
  transform: rotate(6deg);
  opacity: 0;
}
.wj-lamp::before {
  content: '';
  position: absolute;
  top: 10px;
  left: 4px;
  width: 55px;
  height: 32px;
  border-radius: 50% 50% 7px 7px;
  background: linear-gradient(120deg, #dae8cb, #aac796);
  transform: rotate(-18deg);
  box-shadow: inset 4px 4px 8px #fff6;
}
.wj-lamp::after {
  content: '';
  position: absolute;
  left: 29px;
  top: 37px;
  width: 5px;
  height: 68px;
  background: #adc598;
  transform: rotate(4deg);
  border-radius: 5px;
}
.wj-light {
  position: absolute;
  left: 43%;
  top: 34%;
  width: 40%;
  height: 51%;
  background: radial-gradient(ellipse at 80% 0%, #ffe9a36b, transparent 70%);
  z-index: 4;
  opacity: 0;
  pointer-events: none;
}
.wj-idea-label {
  position: absolute;
  z-index: 6;
  padding: 8px 13px;
  border-radius: 6px;
  background: var(--wj-bg);
  border: 1px solid var(--wj-line);
  color: var(--wj-muted);
  box-shadow: 0 5px 13px var(--wj-shadow);
  font-size: 12px;
  opacity: 0;
}
.wj-label-one {
  left: 14%;
  top: 18%;
  transform: rotate(-10deg);
}
.wj-label-two {
  left: 71%;
  top: 11%;
  transform: rotate(8deg);
}
.wj-label-three {
  left: 79%;
  top: 64%;
  transform: rotate(6deg);
}
.wj-room .wj-blue {
  left: 7%;
  bottom: 12%;
}
.wj-room .wj-yellow {
  left: 26%;
  bottom: 30%;
  z-index: 2;
}
.wj-room .wj-pink {
  left: 65%;
  bottom: 2%;
}
.wj-room .wj-green {
  left: 83%;
  bottom: 18%;
}
.wj-room .wj-blue .wj-hand.r {
  transform: rotate(-72deg);
  top: 45%;
  height: 43%;
}
.wj-room .wj-green .wj-hand.l {
  transform: rotate(75deg);
  top: 44%;
  height: 40%;
}
.wj-book-scene .wj-wash {
  background: radial-gradient(ellipse at 50% 50%, light-dark(#e9e5ff, #363050), transparent 66%);
}
.wj-big-book {
  position: absolute;
  left: 21%;
  width: 58%;
  top: 15%;
  height: 70%;
  perspective: 1000px;
  transform: rotate(-5deg);
  z-index: 3;
}
.wj-cover {
  position: absolute;
  inset: 0;
  border: 8px solid light-dark(#a7a2df, #66638c);
  border-radius: 10px 16px 16px 10px;
  background: var(--wj-paper);
  box-shadow:
    0 7px 0 light-dark(#cdc9e5, #454058),
    0 15px 0 light-dark(#a7a2df, #66638c),
    0 35px 45px var(--wj-shadow);
  display: flex;
  align-items: center;
  justify-content: center;
  transform-origin: left center;
  z-index: 4;
  backface-visibility: hidden;
}
.wj-cover span {
  font-size: 21px;
  letter-spacing: 0.13em;
  color: var(--wj-accent);
  border: 1px solid var(--wj-line);
  padding: 20px;
  border-radius: 50%;
  background: var(--wj-bg);
}
.wj-leaf {
  position: absolute;
  inset: 7px;
  border-radius: 5px;
  background: linear-gradient(
    90deg,
    var(--wj-paper),
    var(--wj-bg) 47%,
    var(--wj-line) 49.7%,
    var(--wj-bg) 51%,
    var(--wj-paper)
  );
  box-shadow:
    0 4px 1px var(--wj-line),
    0 7px 0 var(--wj-bg),
    0 10px 0 light-dark(#a7a2df, #66638c);
}
.wj-leaf::before {
  content: '';
  position: absolute;
  left: 8%;
  right: 58%;
  top: 26%;
  height: 47%;
  background: repeating-linear-gradient(to bottom, var(--wj-line) 0 1px, transparent 1px 23px);
}
.wj-page-door {
  position: absolute;
  left: 57%;
  top: 19%;
  width: 32%;
  height: 58%;
  border-radius: 45% 45% 4px 4px;
  background: radial-gradient(ellipse at 60% 24%, light-dark(#fff5c6, #806340), var(--wj-bg) 80%);
  border: 1px solid color-mix(in srgb, var(--wj-accent) 20%, var(--wj-line));
  box-shadow: 0 0 30px color-mix(in srgb, var(--wj-accent) 10%, transparent);
  opacity: 0;
  z-index: 2;
}
.wj-page-door::after {
  content: '';
  position: absolute;
  left: 23%;
  top: 35%;
  width: 3px;
  height: 31%;
  background: var(--wj-accent);
  border-radius: 3px;
}
.wj-page-corner {
  position: absolute;
  bottom: 0;
  right: 0;
  width: 70px;
  height: 70px;
  border-radius: 0 0 15px 0;
  background: linear-gradient(
    135deg,
    transparent 49%,
    light-dark(#e9e5ff, #444056) 50%,
    var(--wj-bg) 95%
  );
  filter: drop-shadow(-3px -3px 3px var(--wj-shadow));
  z-index: 5;
}
.wj-book-scene .wj-blue {
  left: 7%;
  bottom: 2%;
}
.wj-book-scene .wj-yellow {
  left: 22%;
  bottom: -1%;
}
.wj-book-scene .wj-pink {
  left: 74%;
  bottom: 0;
}
.wj-book-scene .wj-green {
  left: 86%;
  bottom: 6%;
}
.wj-fly-leaf {
  position: absolute;
  width: 65px;
  height: 85px;
  border-radius: 4px;
  background: var(--wj-bg);
  border: 1px solid var(--wj-line);
  box-shadow: 0 8px 25px var(--wj-shadow);
  opacity: 0;
}
.wj-fly-leaf.a {
  left: 13%;
  top: 3%;
  transform: rotate(-24deg);
}
.wj-fly-leaf.b {
  right: 6%;
  top: 8%;
  transform: rotate(18deg);
}
.wj-fly-leaf.c {
  left: 3%;
  top: 38%;
  transform: rotate(-10deg);
}
.wj-chase-scene .wj-wash {
  background: radial-gradient(ellipse at 50% 55%, light-dark(#e2f4f3, #29454a), transparent 68%);
}
.wj-chase-floor {
  position: absolute;
  left: 8%;
  right: 8%;
  bottom: 4%;
  height: 28%;
  border-radius: 50%;
  background: linear-gradient(145deg, light-dark(#edf0e3, #313e3c), light-dark(#e2f1ed, #293b3e));
  box-shadow: 0 25px 35px -20px var(--wj-shadow);
}
.wj-trail {
  position: absolute;
  left: 21%;
  top: 20%;
  width: 59%;
  height: 56%;
  border: 2px dashed color-mix(in srgb, var(--wj-accent) 15%, transparent);
  border-bottom: 0;
  border-radius: 50%;
  transform: rotate(-17deg);
  opacity: 0;
}
.wj-orb {
  position: absolute;
  width: 58px;
  height: 58px;
  left: 46%;
  top: 4%;
  border-radius: 43% 55% 47% 50%;
  background: radial-gradient(circle at 31% 28%, #fffdeb, #ffdf7e 55%, #e5b950);
  box-shadow:
    0 0 35px #f1cd7955,
    inset -4px -7px 9px #bc8e4225;
  z-index: 8;
}
.wj-orb::after {
  content: '';
  position: absolute;
  top: 23px;
  left: 18px;
  width: 4px;
  height: 5px;
  border-radius: 50%;
  background: #745a36;
  box-shadow: 15px 0 #745a36;
}
.wj-orb::before {
  content: '';
  position: absolute;
  width: 18px;
  height: 5px;
  border-radius: 50%;
  background: #fffef199;
  left: 10px;
  top: 10px;
  transform: rotate(-32deg);
}
.wj-landing-page {
  position: absolute;
  left: 36%;
  top: 20%;
  width: 33%;
  height: 52%;
  border-radius: 8px;
  background: var(--wj-bg);
  border: 1px solid var(--wj-line);
  box-shadow:
    0 8px 0 var(--wj-paper),
    0 23px 35px var(--wj-shadow);
  opacity: 0;
  transform: rotate(-8deg);
  z-index: 3;
}
.wj-landing-page::after {
  content: '';
  position: absolute;
  left: 18%;
  top: 20%;
  width: 3px;
  height: 29px;
  background: var(--wj-accent);
  opacity: 0;
}
.wj-chase-scene .wj-blue {
  left: 9%;
  bottom: 13%;
}
.wj-chase-scene .wj-yellow {
  left: 30%;
  bottom: 2%;
}
.wj-chase-scene .wj-pink {
  left: 56%;
  bottom: 7%;
}
.wj-chase-scene .wj-green {
  left: 79%;
  bottom: 16%;
}
.wj-net {
  position: absolute;
  z-index: 7;
  left: 78%;
  top: 30%;
  width: 75px;
  height: 100px;
  transform: rotate(-35deg);
  opacity: 0;
}
.wj-net::before {
  content: '';
  position: absolute;
  width: 65px;
  height: 48px;
  border: 3px solid #a7bed0;
  border-radius: 50%;
  background:
    repeating-linear-gradient(45deg, transparent 0 9px, #acbfd244 10px 11px),
    repeating-linear-gradient(-45deg, transparent 0 9px, #acbfd244 10px 11px);
}
.wj-net::after {
  content: '';
  position: absolute;
  height: 62px;
  width: 5px;
  border-radius: 6px;
  background: #c6b69f;
  top: 43px;
  left: 32px;
}
.wj-room .wj-desk {
  animation: wj-build-desk var(--wj-duration) both;
}
.wj-room .wj-desk-paper {
  animation: wj-lay-paper var(--wj-duration) both;
}
.wj-room .wj-pencil {
  animation: wj-deliver-pencil var(--wj-duration) both;
}
.wj-room .wj-lamp {
  animation: wj-place-lamp var(--wj-duration) both;
}
.wj-room .wj-light {
  animation: wj-light-up var(--wj-duration) both;
}
.wj-room .wj-blue {
  animation: wj-builder-blue var(--wj-duration) both;
}
.wj-room .wj-yellow {
  animation: wj-builder-yellow var(--wj-duration) both;
}
.wj-room .wj-pink {
  animation: wj-builder-pink var(--wj-duration) both;
}
.wj-room .wj-green {
  animation: wj-builder-green var(--wj-duration) both;
}
.wj-idea-label {
  animation: wj-sticky var(--wj-duration) both;
}
.wj-book-scene .wj-big-book {
  animation: wj-book-world var(--wj-duration) both;
}
.wj-book-scene .wj-cover {
  animation: wj-book-open var(--wj-duration) both;
}
.wj-book-scene .wj-page-door {
  animation: wj-door var(--wj-duration) both;
}
.wj-book-scene .wj-actor {
  animation: wj-follow-book var(--wj-duration) both;
  --wj-walk: 50px;
}
.wj-book-scene .wj-yellow {
  --wj-walk: 30px;
}
.wj-book-scene .wj-pink {
  --wj-walk: -40px;
}
.wj-book-scene .wj-green {
  --wj-walk: -70px;
}
.wj-fly-leaf {
  animation: wj-pages-fly var(--wj-duration) both;
}
.wj-chase-scene .wj-orb {
  animation: wj-runaway var(--wj-duration) both;
}
.wj-chase-scene .wj-blue {
  animation: wj-chase-blue var(--wj-duration) both;
}
.wj-chase-scene .wj-yellow {
  animation: wj-chase-yellow var(--wj-duration) both;
}
.wj-chase-scene .wj-pink {
  animation: wj-chase-pink var(--wj-duration) both;
}
.wj-chase-scene .wj-green {
  animation: wj-chase-green var(--wj-duration) both;
}
.wj-chase-scene .wj-net {
  animation: wj-catch-net var(--wj-duration) both;
}
.wj-chase-scene .wj-trail {
  animation: wj-trail var(--wj-duration) both;
}
.wj-chase-scene .wj-landing-page {
  animation: wj-catch-page var(--wj-duration) both;
}
.wj-chase-scene .wj-landing-page::after {
  animation: wj-ink var(--wj-duration) both;
}
@keyframes wj-reveal {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
@keyframes wj-focus {
  0% {
    box-shadow:
      0 0 0 3px color-mix(in srgb, var(--wj-accent) 20%, transparent),
      0 5px 50px var(--wj-shadow);
    transform: translateY(8px);
  }
  100% {
    box-shadow: none;
    transform: translateY(0);
  }
}
@keyframes wj-build-desk {
  0%,
  6% {
    opacity: 0;
    transform: translateY(70px) rotateX(44deg) rotateZ(-5deg) scale(0.7);
  }
  22%,
  100% {
    opacity: 1;
    transform: translateY(0) rotateX(44deg) rotateZ(-5deg) scale(1);
  }
}
@keyframes wj-lay-paper {
  0%,
  15% {
    opacity: 0;
    transform: translate(-110px, 25px) rotate(-30deg) scale(0.5);
  }
  36%,
  82% {
    opacity: 1;
    transform: translate(0, 0) rotate(-6deg) scale(1);
  }
  100% {
    opacity: 1;
    transform: translate(0, 0) rotate(0) scale(1.08);
  }
}
@keyframes wj-deliver-pencil {
  0%,
  28% {
    opacity: 0;
    transform: translate(110px, 80px) rotate(-65deg);
  }
  38% {
    opacity: 1;
  }
  63%,
  80% {
    opacity: 1;
    transform: translate(0, 0) rotate(-30deg);
  }
  90%,
  100% {
    opacity: 1;
    transform: translate(-7px, 4px) rotate(-30deg);
  }
}
@keyframes wj-place-lamp {
  0%,
  35% {
    opacity: 0;
    transform: translate(90px, 15px) rotate(20deg);
  }
  61%,
  100% {
    opacity: 1;
    transform: translate(0, 0) rotate(6deg);
  }
}
@keyframes wj-light-up {
  0%,
  61% {
    opacity: 0;
  }
  70%,
  100% {
    opacity: 1;
  }
}
@keyframes wj-builder-blue {
  0% {
    transform: translate(110px, 38px) rotate(10deg);
    opacity: 0;
  }
  14% {
    opacity: 1;
  }
  26% {
    transform: translate(42px, -7px) rotate(-12deg);
  }
  44% {
    transform: translate(28px, 0) rotate(7deg);
  }
  64% {
    transform: translate(0, 0) rotate(-2deg);
  }
  80%,
  100% {
    transform: translate(0, 0);
  }
}
@keyframes wj-builder-yellow {
  0%,
  10% {
    transform: translate(-60px, 20px) scale(0.8);
    opacity: 0;
  }
  28% {
    opacity: 1;
    transform: translate(0, -13px) rotate(-6deg);
  }
  42% {
    transform: translate(0, 0) rotate(5deg);
  }
  70%,
  100% {
    opacity: 1;
    transform: translate(0, 0);
  }
}
@keyframes wj-builder-pink {
  0%,
  12% {
    transform: translate(90px, 15px);
    opacity: 0;
  }
  29% {
    opacity: 1;
    transform: translate(35px, -22px) rotate(-10deg);
  }
  44% {
    transform: translate(0, 0) rotate(4deg);
  }
  60% {
    transform: translate(0, -17px) rotate(-5deg);
  }
  72%,
  100% {
    opacity: 1;
    transform: translate(0, 0);
  }
}
@keyframes wj-builder-green {
  0%,
  24% {
    transform: translate(90px, 15px);
    opacity: 0;
  }
  41% {
    opacity: 1;
    transform: translate(0, -27px) rotate(-12deg);
  }
  53% {
    transform: translate(-10px, 0) rotate(6deg);
  }
  63% {
    transform: translate(-10px, -8px) rotate(-10deg);
  }
  75%,
  100% {
    opacity: 1;
    transform: translate(0, 0);
  }
}
@keyframes wj-sticky {
  0%,
  61% {
    opacity: 0;
    translate: 0 18px;
    scale: 0.7;
  }
  77%,
  100% {
    opacity: 0.9;
    translate: 0 0;
    scale: 1;
  }
}
@keyframes wj-book-world {
  0% {
    opacity: 0;
    transform: translateY(45px) rotate(-18deg) scale(0.32);
  }
  22% {
    opacity: 1;
    transform: translateY(0) rotate(-5deg) scale(0.85);
  }
  44%,
  75% {
    opacity: 1;
    transform: rotate(-5deg) scale(1);
  }
  100% {
    opacity: 1;
    transform: translate(-13%, 8%) rotate(0) scale(1.42);
  }
}
@keyframes wj-book-open {
  0%,
  22% {
    transform: rotateY(0);
  }
  53%,
  100% {
    transform: rotateY(-165deg);
  }
}
@keyframes wj-door {
  0%,
  45% {
    opacity: 0;
    transform: scale(0.7);
  }
  64%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
}
@keyframes wj-follow-book {
  0% {
    opacity: 0;
    transform: translateY(30px);
  }
  20%,
  42% {
    opacity: 1;
    transform: translateY(0);
  }
  60% {
    transform: translate(calc(var(--wj-walk) * 0.3), -13px) rotate(-7deg);
  }
  76% {
    opacity: 1;
    transform: translate(calc(var(--wj-walk) * 0.6), 0) rotate(5deg);
  }
  100% {
    opacity: 0;
    transform: translate(var(--wj-walk), -65px) scale(0.2);
  }
}
@keyframes wj-pages-fly {
  0%,
  22% {
    opacity: 0;
    translate: 0 25px;
    scale: 0.6;
  }
  45% {
    opacity: 1;
    translate: 0 0;
    scale: 1;
  }
  75% {
    opacity: 0.8;
    translate: 0 -20px;
  }
  100% {
    opacity: 0;
    translate: 0 -90px;
    scale: 1.2;
  }
}
@keyframes wj-runaway {
  0% {
    left: 16%;
    top: 48%;
    transform: scale(0.2);
    opacity: 0;
  }
  10% {
    opacity: 1;
    transform: scale(1);
  }
  23% {
    left: 30%;
    top: 4%;
    transform: rotate(-20deg);
  }
  39% {
    left: 73%;
    top: 21%;
    transform: rotate(20deg);
  }
  54% {
    left: calc(50% - 29px);
    top: 4%;
    transform: rotate(-15deg);
  }
  68% {
    left: calc(50% - 29px);
    top: 4%;
    transform: scale(1.05);
  }
  78% {
    left: calc(50% - 29px);
    top: 4%;
    transform: scale(1);
  }
  100% {
    left: 42%;
    top: 32%;
    transform: scale(0.06, 1);
    opacity: 0;
  }
}
@keyframes wj-chase-blue {
  0% {
    transform: translateX(-40px);
    opacity: 0;
  }
  17% {
    opacity: 1;
    transform: translate(24px, -17px) rotate(12deg);
  }
  32% {
    transform: translate(45px, 0) rotate(-8deg);
  }
  55%,
  100% {
    opacity: 1;
    transform: translate(0, 0);
  }
}
@keyframes wj-chase-yellow {
  0% {
    opacity: 0;
  }
  15% {
    opacity: 1;
  }
  27% {
    transform: translate(0, -37px) rotate(-12deg);
  }
  42% {
    transform: translate(18px, 0) rotate(8deg);
  }
  60%,
  100% {
    opacity: 1;
    transform: translate(0, 0);
  }
}
@keyframes wj-chase-pink {
  0%,
  8% {
    opacity: 0;
  }
  20% {
    opacity: 1;
  }
  35% {
    transform: translate(28px, -40px) rotate(18deg);
  }
  49% {
    transform: translate(10px, 0) rotate(-8deg);
  }
  64% {
    transform: translate(-4px, -18px);
  }
  79%,
  100% {
    opacity: 1;
    transform: translate(0, 0);
  }
}
@keyframes wj-chase-green {
  0%,
  18% {
    opacity: 0;
    transform: translateX(40px);
  }
  29% {
    opacity: 1;
  }
  41% {
    transform: translate(0, -47px) rotate(-20deg);
  }
  53% {
    transform: translate(-12px, 0) rotate(7deg);
  }
  70%,
  100% {
    opacity: 1;
    transform: translate(0, 0);
  }
}
@keyframes wj-catch-net {
  0%,
  26% {
    opacity: 0;
    transform: translate(20px, 30px) rotate(0);
  }
  38% {
    opacity: 1;
    transform: translate(0, -18px) rotate(-35deg);
  }
  54% {
    opacity: 1;
    transform: translate(-18px, 15px) rotate(-75deg);
  }
  68%,
  100% {
    opacity: 0;
    transform: translate(-18px, 15px) rotate(-75deg);
  }
}
@keyframes wj-trail {
  0%,
  13% {
    opacity: 0;
  }
  33%,
  54% {
    opacity: 1;
  }
  75%,
  100% {
    opacity: 0;
  }
}
@keyframes wj-catch-page {
  0%,
  69% {
    opacity: 0;
    transform: translateY(30px) rotate(10deg) scale(0.4);
  }
  83% {
    opacity: 1;
    transform: translateY(0) rotate(-8deg) scale(1);
  }
  100% {
    opacity: 1;
    transform: rotate(0) scale(1.12);
  }
}
@keyframes wj-ink {
  0%,
  91% {
    opacity: 0;
  }
  100% {
    opacity: 1;
  }
}
.wj-theater {
  max-width: 1100px;
  margin-inline: auto;
}
.wj-catch {
  position: absolute;
  z-index: 12;
  left: calc(50% - 60px);
  top: 2%;
  width: 120px;
  height: 105px;
  background: transparent;
  border: 0;
  border-radius: 50%;
  cursor: pointer;
  pointer-events: auto;
}
.wj-catch span {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  text-align: center;
  font-size: 12px;
  color: var(--wao-text-weak);
  white-space: nowrap;
}
.wj-catch:hover {
  background: color-mix(in srgb, var(--wj-glow) 25%, transparent);
}
.wj-catch:focus-visible {
  outline: 2px solid var(--wao-primary-text);
  outline-offset: 3px;
}
.paused *,
.paused *::after,
.paused *::before {
  animation-play-state: paused !important;
}
.caught .wj-orb {
  animation: none;
  opacity: 0;
}
.caught .wj-landing-page {
  animation: none;
  opacity: 1;
  transform: rotate(-8deg);
}
.caught .wj-landing-page::after {
  animation: none;
  opacity: 1;
}
.leaving .wj-theater {
  transform: scale(1.08);
  opacity: 0;
  transition:
    transform 0.5s,
    opacity 0.5s;
}
@media (max-width: 760px) {
  .wj-theater {
    left: 3%;
    right: 3%;
    top: 35%;
    height: 49%;
  }
  .wj-idea-label {
    padding: 6px 8px;
    font-size: 11px;
  }
  .wj-theater .wj-eyes {
    transform: scale(0.75);
  }
}
@media (max-width: 430px) {
  .wj-theater {
    top: 37%;
    height: 39%;
  }
  .wj-theater .wj-actor {
    width: 16cqw;
    height: 21cqw;
  }
  .wj-theater .wj-yellow {
    height: 24cqw;
  }
  .wj-theater .wj-green {
    height: 18cqw;
  }
  .wj-theater .wj-pink {
    height: 19cqw;
  }
  .wj-theater .wj-eyes {
    transform: scale(0.58);
    transform-origin: top center;
  }
  .wj-smile {
    scale: 0.65;
  }
  .wj-hat {
    height: 13px;
  }
  .wj-hand {
    width: 10px;
    height: 23px;
  }
  .wj-room .wj-green,
  .wj-book-scene .wj-green {
    left: 80%;
  }
  .wj-book-scene .wj-pink {
    left: 61%;
  }
  .wj-lamp {
    scale: 0.65;
    transform-origin: top left;
    left: 70%;
  }
  .wj-label-three {
    left: 62%;
    top: 88%;
  }
  .wj-label-two {
    left: 61%;
    top: 2%;
  }
  .wj-big-book {
    left: 17%;
    width: 64%;
  }
  .wj-cover {
    border-width: 5px;
  }
  .wj-cover span {
    font-size: 15px;
    padding: 12px;
  }
  .wj-fly-leaf {
    width: 38px;
    height: 55px;
  }
  .wj-orb {
    width: 40px;
    height: 40px;
  }
  .wj-orb::after {
    top: 18px;
    left: 11px;
    box-shadow: 13px 0 #745a36;
  }
  .wj-net {
    scale: 0.6;
    transform-origin: top left;
  }
  .wj-catch {
    width: 100px;
    left: calc(50% - 50px);
    height: 85px;
  }
}
@media (max-height: 500px) {
  .wj-theater {
    top: 30%;
    height: 55%;
    max-width: 700px;
  }
  .wj-theater .wj-actor {
    max-height: 100px;
    max-width: 80px;
  }
  .wj-idea-label {
    display: none;
  }
}
@media (prefers-reduced-motion: reduce) {
  .wj-scene {
    display: none;
  }
}
</style>
