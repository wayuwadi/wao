<script setup lang="ts">
import { computed } from "vue";
import { elementColors, heroes, type Element, type HeroId } from "./catalog";
const props = withDefaults(
  defineProps<{
    kind: string;
    element: Element;
    hurt?: boolean;
    active?: boolean;
    fainted?: boolean;
  }>(),
  { hurt: false, active: false, fainted: false },
);
const shape = computed(() => heroes[props.kind as HeroId]?.shape ?? props.kind);
const color = computed(() => elementColors[props.element]);
</script>
<template>
  <svg
    viewBox="0 0 120 116"
    aria-hidden="true"
    class="character"
    :class="{ hurt, active, fainted }"
  >
    <ellipse cx="60" cy="103" rx="31" ry="7" fill="#65502a" opacity=".14" />
    <g class="body" :style="{ '--ink': color }">
      <path
        d="M42 82 39 98Q44 103 51 98L54 87M68 87 72 98Q79 102 84 97L79 82"
        :fill="color"
        stroke="#574729"
        stroke-width="2"
      />
      <path
        d="M34 57Q20 60 23 79L35 72M84 56Q101 61 97 80L86 71"
        :fill="color"
        stroke="#574729"
        stroke-width="2"
      />
      <path
        v-if="shape === 'sun'"
        d="M38 23 33 9 49 15 59 3 67 16 84 9 82 25 96 33 83 45 38 43 24 33Z"
        fill="#ffe486"
        stroke="#a57736"
        stroke-width="2"
      />
      <path
        v-else-if="shape === 'leaf'"
        d="M54 28Q34 2 73 4 92 19 60 34"
        fill="#8ac298"
        stroke="#4d8861"
        stroke-width="2"
      />
      <path
        v-else-if="shape === 'fox' || shape === 'scout'"
        d="M32 37 22 9 48 26M72 26 99 9 89 39"
        :fill="color"
        stroke="#574729"
        stroke-width="2"
      />
      <path
        v-else-if="shape === 'gem' || shape === 'orrery'"
        d="M28 33 44 7 76 7 95 34 60 57Z"
        fill="#edd8ff"
        stroke="#816693"
        stroke-width="2"
      />
      <path
        v-else-if="shape === 'drop' || shape === 'medic'"
        d="M44 29Q39 14 59 3 72 13 74 30"
        fill="#a5d8e7"
        stroke="#5d93a1"
        stroke-width="2"
      />
      <path
        v-else-if="shape === 'stinger'"
        d="M36 49Q0 20 11 60L38 65M82 47Q119 20 110 62L83 65"
        fill="#fff4be"
        stroke="#c49b49"
        stroke-width="2"
      />
      <path
        v-else-if="shape === 'sundial'"
        d="M16 36 28 14 49 18 65 3 80 20 103 22 108 47"
        fill="#ffcf79"
        stroke="#9f733c"
        stroke-width="3"
      />
      <rect
        x="31"
        y="29"
        width="59"
        height="60"
        rx="23"
        :fill="color"
        stroke="#574729"
        stroke-width="2.5"
      />
      <ellipse cx="60" cy="67" rx="23" ry="19" fill="#fff7dc" />
      <path
        v-if="shape === 'stone' || shape === 'bulwark'"
        d="M31 42 39 29 82 29 90 44 61 37Z"
        fill="#bad0a4"
        stroke="#574729"
        stroke-width="2"
      />
      <path
        v-if="shape === 'librarian' || shape === 'chanter'"
        d="M22 34 35 20 88 18 99 35 60 45Z"
        fill="#665888"
        stroke="#574729"
        stroke-width="2"
      />
      <path
        v-if="fainted"
        d="m43 53 8 8m-8 0 8-8m18 0 8 8m-8 0 8-8"
        stroke="#574729"
        stroke-width="3"
        stroke-linecap="round"
      />
      <g v-else>
        <ellipse cx="48" cy="56" rx="3" ry="4.5" fill="#3d3845" />
        <ellipse cx="73" cy="56" rx="3" ry="4.5" fill="#3d3845" />
        <path
          d="M55 67Q61 73 67 66"
          fill="none"
          stroke="#574729"
          stroke-width="2"
          stroke-linecap="round"
        />
      </g>
      <ellipse cx="42" cy="65" rx="5" ry="3" fill="#edb39b" opacity=".8" />
      <ellipse cx="79" cy="65" rx="5" ry="3" fill="#edb39b" opacity=".8" />
      <path
        v-if="shape === 'fox' || shape === 'drainer'"
        d="M33 80Q6 82 16 100 35 103 40 89"
        :fill="color"
        stroke="#574729"
        stroke-width="2"
      />
      <path
        v-if="shape === 'orrery'"
        d="M13 63Q59 93 109 50M16 51Q59 21 105 70"
        fill="none"
        stroke="#e4bd55"
        stroke-width="4"
      />
      <circle
        v-if="shape === 'sundial'"
        cx="61"
        cy="79"
        r="12"
        fill="#ffde8d"
        stroke="#996c32"
        stroke-width="2"
      />
      <path
        v-if="shape === 'librarian'"
        d="M38 76 58 80 78 74 78 94 58 99 38 94Z"
        fill="#faf0c1"
        stroke="#574729"
        stroke-width="2"
      />
    </g>
  </svg>
</template>
<style scoped>
.character {
  width: 100%;
  height: 100%;
  overflow: visible;
}
.body {
  transform-origin: 60px 102px;
}
.active .body {
  animation: bob 0.9s ease-in-out infinite alternate;
}
.hurt .body {
  animation: hit 0.3s ease-out;
}
.fainted {
  opacity: 0.42;
  filter: grayscale(0.7);
}
@keyframes bob {
  to {
    transform: translateY(-4px) rotate(2deg);
  }
}
@keyframes hit {
  25% {
    transform: translateX(-7px);
  }
  60% {
    transform: translateX(5px);
  }
}
:global(.paused) .body {
  animation-play-state: paused;
}
@media (prefers-reduced-motion: reduce) {
  .body {
    animation: none !important;
  }
}
</style>
